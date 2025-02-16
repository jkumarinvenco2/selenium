// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.router;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.openqa.selenium.grid.data.Availability.DOWN;
import static org.openqa.selenium.grid.data.Availability.UP;
import static org.openqa.selenium.remote.Dialect.W3C;
import static org.openqa.selenium.remote.http.Contents.asJson;
import static org.openqa.selenium.remote.http.HttpMethod.POST;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.events.local.GuavaEventBus;
import org.openqa.selenium.events.zeromq.ZeroMqEventBus;
import org.openqa.selenium.grid.config.MapConfig;
import org.openqa.selenium.grid.data.Availability;
import org.openqa.selenium.grid.data.CreateSessionResponse;
import org.openqa.selenium.grid.data.DefaultSlotMatcher;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.RequestId;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.data.SessionRequest;
import org.openqa.selenium.grid.distributor.Distributor;
import org.openqa.selenium.grid.distributor.local.LocalDistributor;
import org.openqa.selenium.grid.distributor.selector.DefaultSlotSelector;
import org.openqa.selenium.grid.node.HealthCheck;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.node.local.LocalNode;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.grid.server.Server;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.sessionmap.local.LocalSessionMap;
import org.openqa.selenium.grid.sessionqueue.NewSessionQueue;
import org.openqa.selenium.grid.sessionqueue.local.LocalNewSessionQueue;
import org.openqa.selenium.grid.testing.PassthroughHttpClient;
import org.openqa.selenium.grid.testing.TestSessionFactory;
import org.openqa.selenium.grid.web.CombinedHandler;
import org.openqa.selenium.grid.web.Values;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.netty.server.NettyServer;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.DefaultTestTracer;
import org.openqa.selenium.remote.tracing.Tracer;
import org.openqa.selenium.support.ui.FluentWait;
import org.zeromq.ZContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class SessionCleanUpTest {

  public static final Json JSON = new Json();
  private Tracer tracer;
  private EventBus events;
  private HttpClient.Factory clientFactory;
  private Secret registrationSecret;
  private Server<?> server;

  @Before
  public void setup() {
    tracer = DefaultTestTracer.createTracer();
    registrationSecret = new Secret("hereford hop");
    events = ZeroMqEventBus.create(
      new ZContext(),
      "tcp://localhost:" + PortProber.findFreePort(),
      "tcp://localhost:" + PortProber.findFreePort(),
      true,
      registrationSecret);
    clientFactory = HttpClient.Factory.createDefault();
  }

  @After
  public void stopServer() {
    if (server != null) {
      server.stop();
    }

    if (events != null) {
      events.close();
    }
  }

  @Test
  public void shouldRemoveSessionAfterNodeIsShutDownGracefully()
    throws URISyntaxException {
    Capabilities capabilities = new ImmutableCapabilities("browserName", "cheese");
    CombinedHandler handler = new CombinedHandler();
    CombinedHandler nodeHandler = new CombinedHandler();

    SessionMap sessions = new LocalSessionMap(tracer, events);
    handler.addHandler(sessions);
    NewSessionQueue queue = new LocalNewSessionQueue(
      tracer,
      events,
      new DefaultSlotMatcher(),
      Duration.ofSeconds(2),
      Duration.ofSeconds(10),
      registrationSecret);
    handler.addHandler(queue);

    LocalDistributor distributor = new LocalDistributor(
      tracer,
      events,
      clientFactory,
      sessions,
      queue,
      new DefaultSlotSelector(),
      registrationSecret,
      Duration.ofSeconds(1),
      false);
    handler.addHandler(distributor);

    Router router = new Router(tracer, clientFactory, sessions, queue, distributor);
    handler.addHandler(router);

    server = new NettyServer(
      new BaseServerOptions(
        new MapConfig(ImmutableMap.of())),
      handler);

    server.start();

    URI serverUri = server.getUrl().toURI();
    TestSessionFactory sessionFactory = new TestSessionFactory((id, caps) -> new Session(
      id,
      serverUri,
      new ImmutableCapabilities(),
      caps,
      Instant.now()));

    Server<?> nodeServer = new NettyServer(
      new BaseServerOptions(
        new MapConfig(ImmutableMap.of())),
      nodeHandler);

    Node localNode = LocalNode.builder(tracer,
                                       events,
                                       nodeServer.getUrl().toURI(),
                                       nodeServer.getUrl().toURI(),
                                       registrationSecret)
      .heartbeatPeriod(Duration.ofSeconds(5))
      .add(capabilities, sessionFactory)
      .advanced()
      .healthCheck(() -> new HealthCheck.Result(UP, "Node availability"))
      .build();
    nodeHandler.addHandler(localNode);

    nodeServer.start();

    waitToHaveCapacity(distributor);

    HttpRequest request = new HttpRequest(POST, "/session");
    request.setContent(asJson(
      ImmutableMap.of(
        "capabilities", ImmutableMap.of(
          "alwaysMatch", capabilities))));

    HttpClient client = clientFactory.createClient(server.getUrl());
    HttpResponse httpResponse = client.execute(request);
    assertThat(httpResponse.getStatus()).isEqualTo(HTTP_OK);

    Optional<Map<String, Object>> maybeResponse =
      Optional.ofNullable(Values.get(httpResponse, Map.class));

    String rawResponse = JSON.toJson(maybeResponse.get().get("sessionId"));
    SessionId id = JSON.toType(rawResponse, SessionId.class);

    Session session = sessions.get(id);

    assertThat(session.getCapabilities()).isEqualTo(capabilities);

    nodeServer.stop();

    waitTillNodesAreRemoved(distributor);

    try {
      waitTillSessionIsRemoved(sessions, id);
    } catch (Exception e) {
      fail("Session not removed");
    }
  }

  @Test
  public void shouldRemoveSessionAfterNodeIsDown() throws URISyntaxException {
    CombinedHandler handler = new CombinedHandler();
    Capabilities capabilities = new ImmutableCapabilities("browserName", "cheese");

    AtomicReference<Availability> availability = new AtomicReference<>(UP);

    SessionMap sessions = new LocalSessionMap(tracer, events);
    handler.addHandler(sessions);
    NewSessionQueue queue = new LocalNewSessionQueue(
      tracer,
      events,
      new DefaultSlotMatcher(),
      Duration.ofSeconds(2),
      Duration.ofSeconds(2),
      registrationSecret);

    URI uri = new URI("http://localhost:" + PortProber.findFreePort());
    Node node = LocalNode.builder(tracer, events, uri, uri, registrationSecret)
      .add(
        capabilities,
        new TestSessionFactory(
          (id, caps) -> new Session(id, uri, capabilities, caps, Instant.now())))
      .heartbeatPeriod(Duration.ofSeconds(500000))
      .advanced()
      .healthCheck(() -> new HealthCheck.Result(availability.get(), "TL;DR"))
      .build();
    handler.addHandler(node);

    LocalDistributor distributor = new LocalDistributor(
      tracer,
      events,
      new PassthroughHttpClient.Factory(handler),
      sessions,
      queue,
      new DefaultSlotSelector(),
      registrationSecret,
      Duration.ofSeconds(1),
      false);
    handler.addHandler(distributor);
    distributor.add(node);

    waitToHaveCapacity(distributor);

    Either<SessionNotCreatedException, CreateSessionResponse> result =
      distributor.newSession(new SessionRequest(
        new RequestId(UUID.randomUUID()),
        Instant.now(),
        ImmutableSet.of(W3C),
        ImmutableSet.of(capabilities),
        ImmutableMap.of(),
        ImmutableMap.of()));
    assertThat(result.isRight()).isTrue();

    SessionId id = result.right().getSession().getId();
    Session session = sessions.get(id);

    assertThat(session.getCapabilities()).isEqualTo(capabilities);

    availability.set(DOWN);

    waitTillNodesAreRemoved(distributor);

    try {
      waitTillSessionIsRemoved(sessions, id);
    } catch (Exception e) {
      fail("Session not removed");
    }
  }

  private void waitToHaveCapacity(Distributor distributor) {
    new FluentWait<>(distributor)
      .withTimeout(Duration.ofSeconds(5))
      .pollingEvery(Duration.ofMillis(100))
      .until(d -> d.getStatus().hasCapacity());
  }

  private void waitTillNodesAreRemoved(Distributor distributor) {
    new FluentWait<>(distributor)
      .withTimeout(Duration.ofSeconds(120))
      .pollingEvery(Duration.ofMillis(100))
      .until(d -> {
        Set<NodeStatus> nodes = d.getStatus().getNodes();
        return nodes.isEmpty();
      });
  }

  private void waitTillSessionIsRemoved(SessionMap sessionMap, SessionId id) {
    new FluentWait<>(sessionMap)
      .withTimeout(Duration.ofSeconds(15))
      .pollingEvery(Duration.ofMillis(100))
      .until(s -> {
        try {
          s.get(id);
        } catch (NoSuchSessionException e) {
          return true;
        }
        return false;
      });
  }
}
