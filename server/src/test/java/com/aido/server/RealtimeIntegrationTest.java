package com.aido.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** WebSocket 实时推送：握手票据、按人推送、不回推发起方、心跳清理死连接、100 人并发。需要本机有 Docker。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.clean-disabled=false",
    // 心跳调快，方便验证死连接会被清理
    "aido.realtime.heartbeat=500ms",
    "aido.realtime.idle-timeout=2s",
})
class RealtimeIntegrationTest {

  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

  @LocalServerPort
  int port;

  @Autowired
  Flyway flyway;

  @Autowired
  JdbcClient jdbc;

  @Autowired
  JsonMapper json;

  @Autowired
  PasswordEncoder passwords;

  final HttpClient http = HttpClient.newHttpClient();
  final List<Client> clients = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void reset() {
    flyway.clean();
    flyway.migrate();
    browsers.clear();
  }

  @AfterEach
  void closeClients() {
    clients.forEach(Client::close);
  }

  // —— 测试用的 WebSocket 客户端：收到 ping 回 pong（silent 时不回，模拟死掉的客户端）——

  class Client implements WebSocket.Listener {
    final String userId;
    final boolean silent;
    final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    final StringBuilder partial = new StringBuilder();
    WebSocket socket;

    Client(String userId, boolean silent) {
      this.userId = userId;
      this.silent = silent;
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        var event = json.readTree(partial.toString());
        partial.setLength(0);
        if ("ping".equals(event.path("type").asString())) {
          if (!silent) ws.sendText("{\"type\":\"pong\"}", true);
        } else {
          events.add(event);
        }
      }
      ws.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
      closed.complete(status);
      return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
      closed.complete(-1);
    }

    /** 等下一条指定类型的事件。 */
    JsonNode next(String type, Duration timeout) throws InterruptedException {
      var deadline = Instant.now().plus(timeout);
      while (Instant.now().isBefore(deadline)) {
        var e = events.poll(Duration.between(Instant.now(), deadline).toMillis(), TimeUnit.MILLISECONDS);
        if (e != null && type.equals(e.path("type").asString())) return e;
      }
      throw new AssertionError(userId + " 没等到 " + type);
    }

    void close() {
      if (socket != null && !socket.isOutputClosed()) socket.abort();
    }
  }

  // 每个 (用户, 标签页) 一个已登录的浏览器会话，复用以免反复登录
  final Map<String, TestSessions.Browser> browsers = new ConcurrentHashMap<>();

  private TestSessions.Browser browser(String user, String clientId) {
    return browsers.computeIfAbsent(user + "|" + clientId, k -> new TestSessions(port).as(user, clientId));
  }

  private RestClient api(String user, String clientId) {
    return browser(user, clientId).api;
  }

  private String ticket(String user) {
    return ticket(user, null);
  }

  private String ticket(String user, String clientId) {
    return api(user, clientId).post().uri("/realtime/ticket").retrieve().body(JsonNode.class).path("ticket").asString();
  }

  private WebSocket open(String ticket, String clientId, WebSocket.Listener listener) {
    var uri = URI.create("ws://localhost:" + port + "/api/realtime?ticket=" + ticket + "&client=" + clientId);
    return http.newWebSocketBuilder().buildAsync(uri, listener).join();
  }

  private Client connect(String user, String clientId, boolean silent) throws InterruptedException {
    var client = new Client(user, silent);
    client.socket = open(ticket(user, clientId), clientId, client);
    clients.add(client);
    client.next("hello", Duration.ofSeconds(5));
    return client;
  }

  private void send(String user, String clientId, String conv, String text) {
    var status = api(user, clientId).post().uri("/conversations/{id}/messages", conv)
        .contentType(MediaType.APPLICATION_JSON).body(Map.of("text", text)).retrieve().toBodilessEntity().getStatusCode();
    assertThat(status.value()).isEqualTo(201);
  }

  /** 握手被拒时的 HTTP 状态码。 */
  private int rejectedStatus(String ticket) {
    try {
      open(ticket, "probe", new Client("probe", false)).abort();
      return 101;
    } catch (CompletionException e) {
      return ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode();
    }
  }

  @Test
  void handshakeNeedsSingleUseTicket() {
    assertThat(rejectedStatus("forged")).isEqualTo(401);

    var t = ticket("me");
    assertThat(rejectedStatus(t)).isEqualTo(101);
    // 同一张票据不能再用
    assertThat(rejectedStatus(t)).isEqualTo(401);
  }

  @Test
  void messageReachesRecipientsButNotTheOriginatingTab() throws Exception {
    var zy = connect("zy", "zy-tab", false);
    var meTabA = connect("me", "me-tab-a", false);
    var meTabB = connect("me", "me-tab-b", false);

    // 林舟在标签页 A 发给周远
    send("me", "me-tab-a", "c4", "@周远 实时推送测试");

    var toZy = zy.next("message.created", Duration.ofSeconds(5)).path("data");
    assertThat(toZy.path("message").path("text").asString()).isEqualTo("@周远 实时推送测试");
    assertThat(toZy.path("message").path("mentionsMe").asBoolean()).isTrue();
    assertThat(toZy.path("unread").asInt()).isEqualTo(1);

    // 自己的另一个标签页同步到；发起的标签页不再收到（它已从 HTTP 响应拿到了）
    var toTabB = meTabB.next("message.created", Duration.ofSeconds(5)).path("data");
    assertThat(toTabB.path("message").path("mentionsMe").asBoolean()).isFalse();
    assertThat(toTabB.path("unread").asInt()).isZero();
    assertThat(meTabA.events.poll(500, TimeUnit.MILLISECONDS)).isNull();

    // 不在会话里的人收不到（c4 是林舟和周远的私聊）
    var csq = connect("csq", "csq-tab", false);
    send("me", "me-tab-a", "c4", "第二条");
    assertThat(csq.events.poll(500, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void todoAndReadStateSyncAcrossTabs() throws Exception {
    var tabB = connect("me", "tab-b", false);
    api("me", "tab-a").patch().uri("/todos/1").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("status", "done")).retrieve().toBodilessEntity();
    var todo = tabB.next("todos.changed", Duration.ofSeconds(5)).path("data").path("todos").get(0);
    assertThat(todo.path("id").asLong()).isEqualTo(1);
    assertThat(todo.path("status").asString()).isEqualTo("done");

    api("me", "tab-a").post().uri("/conversations/c1/read").retrieve().toBodilessEntity();
    assertThat(tabB.next("conversation.read", Duration.ofSeconds(5)).path("data").path("conversationId").asString())
        .isEqualTo("c1");
  }

  @Test
  void memoryPreferencesAndCapabilitiesSyncAcrossTabs() throws Exception {
    var tabA = connect("me", "tab-a", false);
    var tabB = connect("me", "tab-b", false);
    var fromA = api("me", "tab-a");

    // 记忆：推整页快照
    fromA.post().uri("/memory/profile-tags").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("group", "工作习惯", "tag", "午休不开会")).retrieve().toBodilessEntity();
    var memory = tabB.next("memory.changed", Duration.ofSeconds(5)).path("data");
    assertThat(memory.path("profile").findValuesAsString("tag")).contains("午休不开会");
    assertThat(memory.path("items").size()).isEqualTo(4);

    // 偏好：主题、形象、学习开关
    fromA.patch().uri("/me/preferences").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("theme", "dark", "memoryLearning", false)).retrieve().toBodilessEntity();
    var prefs = tabB.next("preferences.changed", Duration.ofSeconds(5)).path("data");
    assertThat(prefs.path("theme").asString()).isEqualTo("dark");
    assertThat(prefs.path("memoryLearning").asBoolean()).isFalse();

    // 能力开关
    fromA.patch().uri("/agent/capabilities/weekly_report").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("enabled", true)).retrieve().toBodilessEntity();
    var caps = tabB.next("capabilities.changed", Duration.ofSeconds(5)).path("data").path("capabilities");
    assertThat(caps.findValues("key").stream().map(JsonNode::asString)).contains("weekly_report");

    // 发起改动的标签页不会收到自己的回推
    assertThat(tabA.events.poll(500, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void profileChangesReachEveryone() throws Exception {
    // 和林舟不在同一个会话里的人也要收到：通讯录里人人可见
    var hj = connect("hj", "hj-tab", false);
    var meOtherTab = connect("me", "me-tab-b", false);
    api("me", "me-tab-a").patch().uri("/me").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("name", "林小舟", "color", "#6366f1")).retrieve().toBodilessEntity();

    for (var client : List.of(hj, meOtherTab)) {
      var user = client.next("user.updated", Duration.ofSeconds(5)).path("data").path("user");
      assertThat(user.path("id").asString()).isEqualTo("me");
      assertThat(user.path("name").asString()).isEqualTo("林小舟");
      assertThat(user.path("color").asString()).isEqualTo("#6366f1");
      // 登录账号不随推送外泄
      assertThat(user.has("login")).isFalse();
    }
  }

  @Test
  void heartbeatClosesSilentConnections() throws Exception {
    var alive = connect("me", "alive", false);
    var dead = connect("zy", "dead", true);
    // idle-timeout=2s：不回 pong 的连接被服务端断开，回 pong 的保持在线
    dead.closed.get(6, TimeUnit.SECONDS);
    assertThat(alive.closed.isDone()).isFalse();
  }

  /**
   * 100 人同时在线，同时在同一个群里各连发 5 条：每人都应收到全部 500 条，共 50000 次推送。
   * 打印端到端延迟（消息落库时间 → 客户端收到）。
   */
  @Test
  void hundredConcurrentUsers() throws Exception {
    int n = 100;
    int perUser = 5;
    int total = n * perUser;
    var users = new ArrayList<String>();
    for (int i = 0; i < n; i++) users.add("load%03d".formatted(i));
    // 压测账号：登录名就是 id，密码同演示账号（哈希算一次，大家共用）
    var hash = passwords.encode(TestSessions.DEMO_PASSWORD);
    for (var u : users) {
      jdbc.sql("INSERT INTO app_user (id, name, login, password_hash) VALUES (?, ?, ?, ?)")
          .params(u, "压测" + u, u, hash).update();
    }
    jdbc.sql("INSERT INTO conversation (id, type, name, member_count) VALUES ('load', 'group', '压测群', ?)").param(n).update();
    for (var u : users) {
      jdbc.sql("INSERT INTO conversation_member (conversation_id, user_id) VALUES ('load', ?)").param(u).update();
    }

    // 并发建立 100 条连接
    var received = new ConcurrentHashMap<String, Set<Long>>();
    var latenciesMs = Collections.synchronizedList(new ArrayList<Long>());
    var allDone = new CountDownLatch(n);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var connecting = users.stream().map(u -> pool.submit(() -> {
        var got = ConcurrentHashMap.<Long>newKeySet();
        received.put(u, got);
        var client = new Client(u, false) {
          @Override
          public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
              var e = json.readTree(partial.toString());
              partial.setLength(0);
              var type = e.path("type").asString();
              if ("ping".equals(type)) ws.sendText("{\"type\":\"pong\"}", true);
              else if ("hello".equals(type)) events.add(e);
              else if ("message.created".equals(type)) {
                var msg = e.path("data").path("message");
                var sentAt = OffsetDateTime.parse(msg.path("sentAt").asString()).toInstant();
                latenciesMs.add(Duration.between(sentAt, Instant.now()).toMillis());
                if (got.add(msg.path("id").asLong()) && got.size() == total) allDone.countDown();
              }
            }
            ws.request(1);
            return null;
          }
        };
        client.socket = open(ticket(u), u + "-ws", client);
        clients.add(client);
        client.next("hello", Duration.ofSeconds(10));
        return null;
      })).toList();
      for (var f : connecting) f.get(20, TimeUnit.SECONDS);
    }

    // 100 人同时发消息
    var start = Instant.now();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var sending = users.stream().map(u -> pool.submit(() -> {
        for (int i = 0; i < perUser; i++) send(u, null, "load", "来自 " + u + " #" + i);
      })).toList();
      for (var f : sending) f.get(20, TimeUnit.SECONDS);
    }
    var sentIn = Duration.between(start, Instant.now());

    assertThat(allDone.await(30, TimeUnit.SECONDS)).as("每人都收到全部 %d 条", total).isTrue();
    var deliveredIn = Duration.between(start, Instant.now());
    assertThat(received.values()).allSatisfy(ids -> assertThat(ids).hasSize(total));

    var sorted = latenciesMs.stream().sorted().toList();
    System.out.printf(
        "[压测] %d 人在线，并发发送 %d 条，推送 %d 次；全部发送完成 %d ms，全部送达 %d ms；延迟 p50=%d ms p99=%d ms max=%d ms%n",
        n, total, sorted.size(), sentIn.toMillis(), deliveredIn.toMillis(),
        sorted.get(sorted.size() / 2), sorted.get((int) (sorted.size() * 0.99)), sorted.getLast());
  }
}
