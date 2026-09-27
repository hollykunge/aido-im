package com.aido.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 用真实 PostgreSQL 跑迁移和演示数据，走 HTTP 验证主要业务流程。需要本机有 Docker。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.flyway.clean-disabled=false")
class ApiIntegrationTest {

  // Spring Boot 会自动启动带 @ServiceConnection 的容器并接上数据源
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

  private static final ParameterizedTypeReference<Map<String, Object>> OBJ = new ParameterizedTypeReference<>() {};
  private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

  @LocalServerPort
  int port;

  @Autowired
  Flyway flyway;

  RestClient api;

  @BeforeEach
  void setUp() {
    // 每个用例前重置到演示数据
    flyway.clean();
    flyway.migrate();
    // 默认以林舟登录
    sessions = new TestSessions(port);
    api = sessions.as("me").api;
  }

  TestSessions sessions;

  private RestClient as(String user) {
    return sessions.as(user).api;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> obj(Object o) {
    return (Map<String, Object>) o;
  }

  private Map<String, Object> post(String path, Object body) {
    return api.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(OBJ);
  }

  @Test
  void conversationsAndBadgesMatchDemoData() {
    var counters = api.get().uri("/workspace/counters").retrieve().body(OBJ);
    // 免打扰的会话（前端技术群 12、全员通知 1）不计入角标
    assertThat(counters).containsEntry("unreadTotal", 41).containsEntry("openTodoCount", 7);

    var convs = api.get().uri("/conversations").retrieve().body(LIST);
    assertThat(convs.getFirst()).containsEntry("id", "c1").containsEntry("pinned", true);
    assertThat(convs).filteredOn(c -> c.get("id").equals("c4")).singleElement()
        .satisfies(c -> assertThat(c).containsEntry("title", "周远").containsEntry("agentFlag", "待回复"));

    var flagged = api.get().uri("/conversations?filter=flag").retrieve().body(LIST);
    assertThat(flagged).extracting(c -> c.get("id")).containsExactly("c1", "c2", "c4");
  }

  @Test
  void sendingMessageUpdatesUnreadAndMentions() {
    var msg = post("/conversations/c4/messages", Map.of("text", "@周远 明天上午给你粗估"));
    assertThat(msg).containsEntry("senderId", "me").containsEntry("mentionsMe", false);

    // 对方未读 +1，且被 @ 到；自己的「待回复」标记被清掉
    var zy = as("zy");
    var theirs = zy.get().uri("/conversations/c4").retrieve().body(OBJ);
    assertThat(theirs).containsEntry("unread", 1).containsEntry("title", "林舟");
    // 按 id 找刚发的这条，不依赖它在列表里的位置
    var sent = zy.get().uri("/conversations/c4/messages").retrieve().body(LIST).stream()
        .filter(m -> m.get("id").equals(msg.get("id"))).findFirst().orElseThrow();
    assertThat(sent).containsEntry("mentionsMe", true);
    assertThat(api.get().uri("/conversations/c4").retrieve().body(OBJ)).doesNotContainKey("agentFlag");
  }

  @Test
  void messageBecomesTodoOnlyOnce() {
    var todo = post("/todos/from-message", Map.of("messageId", 103));
    assertThat(todo).containsEntry("status", "open").containsEntry("today", true);
    assertThat(obj(todo.get("source"))).containsEntry("conversationId", "c1");

    var again = api.post().uri("/todos/from-message").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("messageId", 103)).retrieve().toBodilessEntity();
    assertThat(again.getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void rescanPromotesCandidateThenReportsUpToDate() {
    var first = api.post().uri("/todos/rescan").retrieve().toEntity(OBJ);
    assertThat(first.getStatusCode().value()).isEqualTo(200);
    assertThat(first.getBody()).containsEntry("status", "suggested");

    var second = api.post().uri("/todos/rescan").retrieve().toBodilessEntity();
    assertThat(second.getStatusCode().value()).isEqualTo(204);
  }

  @Test
  void summaryIntentIsNotMistakenForSchedule() {
    // 「总结当前会话」里有「会」字，不能被当成问日程
    var reply = post("/agent/messages", Map.of(
        "text", "总结当前会话", "context", Map.of("view", "chat", "conversationId", "c1")));
    assertThat((List<?>) reply.get("steps")).first().asString().contains("AIDo 5.0 项目组");
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendingDraftCompletesReplyTodo() {
    var reply = post("/agent/messages", Map.of(
        "text", "帮我起草回复", "context", Map.of("view", "chat", "conversationId", "c1")));
    var draft = ((List<Map<String, Object>>) reply.get("items")).getLast();
    assertThat(draft).containsEntry("type", "draft").containsEntry("state", "pending");

    var sent = post("/agent/feed/" + draft.get("id") + "/draft/send", Map.of());
    assertThat(obj(sent.get("item"))).containsEntry("state", "sent");
    assertThat(obj(sent.get("completedTodo"))).containsEntry("id", 1).containsEntry("status", "done");
  }

  @Test
  void usersOnlySeeTheirOwnData() {
    assertThat(as("zy").get().uri("/todos").retrieve().body(LIST)).isEmpty();
    assertThat(as("zy").get().uri("/todos/1").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(404);
    // 周远不在「我和陈思琪」的私聊里
    assertThat(as("zy").get().uri("/conversations/c2/messages").retrieve().toBodilessEntity().getStatusCode().value())
        .isEqualTo(404);
    // 没登录的请求一律 401
    var anonymous = sessions.new Browser(null).api;
    assertThat(anonymous.get().uri("/me").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void searchCoversAllSections() {
    var result = api.get().uri(b -> b.path("/search").queryParam("q", "周远").build()).retrieve().body(OBJ);
    assertThat(obj(result.get("contacts"))).containsEntry("total", 1);
    assertThat(obj(result.get("todos"))).containsEntry("total", 2);

    var files = api.get().uri(b -> b.path("/search").queryParam("q", "交互稿").queryParam("scope", "files").build())
        .retrieve().body(OBJ);
    assertThat(obj(files.get("files"))).containsEntry("total", 2);
  }

  private int patchMe(Map<String, Object> body) {
    return api.patch().uri("/me").contentType(MediaType.APPLICATION_JSON).body(body)
        .retrieve().toBodilessEntity().getStatusCode().value();
  }

  @Test
  void updateOwnNameAndColor() {
    var me = api.patch().uri("/me").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("name", "  林小舟 ", "color", "#6366F1")).retrieve().body(OBJ);
    // 去掉首尾空格，颜色统一小写；返回的仍是完整的本人信息
    assertThat(me).containsEntry("name", "林小舟").containsEntry("color", "#6366f1").containsEntry("login", "linzhou");
    // 别人的通讯录、私聊标题跟着变
    assertThat(as("zy").get().uri("/conversations/c4").retrieve().body(OBJ)).containsEntry("title", "林小舟");

    // 只传一个字段时另一个不变
    assertThat(patchMe(Map.of("color", "#ec4899"))).isEqualTo(200);
    assertThat(api.get().uri("/me").retrieve().body(OBJ)).containsEntry("name", "林小舟").containsEntry("color", "#ec4899");

    assertThat(patchMe(Map.of("color", "pink"))).isEqualTo(400);
    assertThat(patchMe(Map.of("name", "   "))).isEqualTo(400);
    assertThat(patchMe(Map.of("name", "林\n舟"))).isEqualTo(400);
    assertThat(patchMe(Map.of("name", "名".repeat(21)))).isEqualTo(400);
  }
}
