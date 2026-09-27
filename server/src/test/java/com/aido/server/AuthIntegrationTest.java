package com.aido.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/** 登录鉴权：会话、CSRF、限流、退出，以及退出后断开 WebSocket。需要本机有 Docker。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.flyway.clean-disabled=false")
class AuthIntegrationTest {

  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

  @LocalServerPort
  int port;

  @Autowired
  Flyway flyway;

  @Autowired
  JdbcClient jdbc;

  TestSessions sessions;

  @BeforeEach
  void reset() {
    flyway.clean();
    flyway.migrate();
    sessions = new TestSessions(port);
  }

  private static int status(org.springframework.http.ResponseEntity<?> r) {
    return r.getStatusCode().value();
  }

  @Test
  void apiRequiresLogin() {
    var anonymous = sessions.new Browser(null);
    var res = anonymous.api.get().uri("/conversations").retrieve().toEntity(JsonNode.class);
    assertThat(status(res)).isEqualTo(401);
    assertThat(res.getBody().path("detail").asString()).isEqualTo("请先登录");
    // 健康检查不需要登录
    assertThat(status(anonymous.api.get().uri("http://localhost:" + port + "/actuator/health").retrieve().toBodilessEntity()))
        .isEqualTo(200);
  }

  @Test
  void loginReturnsUserAndSetsHardenedSessionCookie() {
    var b = sessions.new Browser(null);
    b.api.get().uri("/me").retrieve().toBodilessEntity();
    var res = b.api.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("login", "LinZhou", "password", TestSessions.DEMO_PASSWORD)).retrieve().toEntity(JsonNode.class);
    // 账号不区分大小写；返回的是用户资料
    assertThat(status(res)).isEqualTo(200);
    assertThat(res.getBody().path("id").asString()).isEqualTo("me");
    var setCookie = String.join(";", res.getHeaders().get("Set-Cookie"));
    assertThat(setCookie).contains("AIDO_SESSION=").contains("HttpOnly").contains("SameSite=Lax");
    // /api/me 额外带本人的登录账号和登录时间；通讯录里不暴露登录账号
    var me = b.api.get().uri("/me").retrieve().body(JsonNode.class);
    assertThat(me.path("login").asString()).isEqualTo("linzhou");
    assertThat(me.path("loggedInAt").asString()).isNotBlank();
    var contacts = b.api.get().uri("/users").retrieve().body(JsonNode.class);
    assertThat(contacts.findValues("login")).isEmpty();
  }

  @Test
  void wrongPasswordAndUnknownAccountLookTheSame() {
    var b = sessions.new Browser(null);
    b.api.get().uri("/me").retrieve().toBodilessEntity();
    var wrong = b.api.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("login", "linzhou", "password", "nope")).retrieve().toEntity(JsonNode.class);
    var unknown = b.api.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("login", "nobody", "password", "nope")).retrieve().toEntity(JsonNode.class);
    assertThat(status(wrong)).isEqualTo(401);
    assertThat(status(unknown)).isEqualTo(401);
    assertThat(wrong.getBody().path("detail").asString()).isEqualTo(unknown.getBody().path("detail").asString());
  }

  @Test
  void tooManyFailuresAreThrottled() {
    var b = sessions.new Browser(null);
    for (int i = 0; i < 5; i++) assertThat(b.login("zhouyuan", "wrong-" + i)).isEqualTo(401);
    // 第 6 次即使密码正确也被拒绝，并告知多久后再试
    var res = b.api.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("login", "zhouyuan", "password", TestSessions.DEMO_PASSWORD)).retrieve().toBodilessEntity();
    assertThat(status(res)).isEqualTo(429);
    assertThat(res.getHeaders().getFirst("Retry-After")).isNotBlank();
  }

  @Test
  void disabledAccountCannotLogIn() {
    jdbc.sql("UPDATE app_user SET disabled = true WHERE id = 'hj'").update();
    assertThat(sessions.new Browser(null).login("hejing", TestSessions.DEMO_PASSWORD)).isEqualTo(403);
  }

  @Test
  void writesWithoutCsrfTokenAreRejected() {
    var b = sessions.as("me");
    // 伪造的跨站请求：带着会话 Cookie，但拿不到 CSRF 令牌
    var forged = org.springframework.web.client.RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api")
        .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(
            HttpClient.newBuilder().cookieHandler(b.cookies).build()))
        .defaultStatusHandler(s -> true, (req, res) -> {})
        .build();
    var res = forged.post().uri("/todos").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("title", "伪造的 TODO")).retrieve().toBodilessEntity();
    assertThat(status(res)).isEqualTo(403);
    // 正常带令牌的请求可以
    var ok = b.api.post().uri("/todos").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("title", "正常的 TODO")).retrieve().toBodilessEntity();
    assertThat(status(ok)).isEqualTo(201);
  }

  @Test
  void loginRotatesCsrfToken() {
    var b = sessions.new Browser(null);
    b.api.get().uri("/me").retrieve().toBodilessEntity();
    var before = b.cookie("XSRF-TOKEN");
    b.api.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("login", "linzhou", "password", TestSessions.DEMO_PASSWORD)).retrieve().toBodilessEntity();
    b.api.get().uri("/me").retrieve().toBodilessEntity();
    assertThat(before).isNotNull();
    assertThat(b.cookie("XSRF-TOKEN")).isNotEqualTo(before);
  }

  @Test
  void logoutEndsSessionAndClosesItsWebSockets() throws Exception {
    var b = sessions.as("me");
    var ticket = b.api.post().uri("/realtime/ticket").retrieve().body(JsonNode.class).path("ticket").asString();
    var closed = new CompletableFuture<Integer>();
    HttpClient.newHttpClient().newWebSocketBuilder()
        .buildAsync(URI.create("ws://localhost:" + port + "/api/realtime?ticket=" + ticket), new WebSocket.Listener() {
          @Override
          public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(code);
            return null;
          }
        }).join();

    assertThat(status(b.api.post().uri("/auth/logout").retrieve().toBodilessEntity())).isEqualTo(204);
    assertThat(status(b.api.get().uri("/me").retrieve().toBodilessEntity())).isEqualTo(401);
    // 4401：会话已结束，客户端据此回到登录页
    assertThat(closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);
    // 退出后也拿不到新票据
    assertThat(status(b.api.post().uri("/realtime/ticket").retrieve().toBodilessEntity())).isEqualTo(401);
  }

  private int changePassword(TestSessions.Browser b, String current, String next) {
    return status(b.api.post().uri("/me/password").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("currentPassword", current, "newPassword", next)).retrieve().toBodilessEntity());
  }

  @Test
  void changePasswordKeepsThisSessionAndEndsOthers() {
    var here = sessions.as("me");
    var elsewhere = sessions.as("me");

    assertThat(changePassword(here, TestSessions.DEMO_PASSWORD, "correct-horse-battery")).isEqualTo(204);

    // 当前会话继续可用（会话 id 换了，Cookie 已更新）；另一处的登录失效
    assertThat(status(here.api.get().uri("/me").retrieve().toBodilessEntity())).isEqualTo(200);
    assertThat(status(elsewhere.api.get().uri("/me").retrieve().toBodilessEntity())).isEqualTo(401);
    // 旧密码不能再登录，新密码可以
    assertThat(sessions.new Browser(null).login("linzhou", TestSessions.DEMO_PASSWORD)).isEqualTo(401);
    assertThat(sessions.new Browser(null).login("linzhou", "correct-horse-battery")).isEqualTo(200);
  }

  @Test
  void changePasswordValidates() {
    var b = sessions.as("me");
    // 当前密码错误返回 400（不是 401，免得前端当成会话失效）
    var wrong = b.api.post().uri("/me/password").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("currentPassword", "nope", "newPassword", "correct-horse-battery")).retrieve().toEntity(JsonNode.class);
    assertThat(status(wrong)).isEqualTo(400);
    assertThat(wrong.getBody().path("detail").asString()).isEqualTo("当前密码不正确");

    assertThat(changePassword(b, TestSessions.DEMO_PASSWORD, "short")).isEqualTo(400);
    assertThat(changePassword(b, TestSessions.DEMO_PASSWORD, TestSessions.DEMO_PASSWORD)).isEqualTo(400);
    assertThat(changePassword(b, TestSessions.DEMO_PASSWORD, "linzhou-2026")).isEqualTo(400);
    assertThat(changePassword(b, TestSessions.DEMO_PASSWORD, "12345678")).isEqualTo(400);
    assertThat(changePassword(b, TestSessions.DEMO_PASSWORD, "密".repeat(25))).isEqualTo(400);
    // 以上都没改成功，原密码仍然有效
    assertThat(sessions.new Browser(null).login("linzhou", TestSessions.DEMO_PASSWORD)).isEqualTo(200);
  }

  @Test
  void wrongCurrentPasswordCountsTowardThrottle() {
    // 限流计数在内存里、同一次测试运行中共享；用一个别的用例没碰过的账号
    var b = sessions.as("csq");
    for (int i = 0; i < 5; i++) {
      assertThat(changePassword(b, "wrong-" + i, "correct-horse-battery")).isEqualTo(400);
    }
    assertThat(changePassword(b, TestSessions.DEMO_PASSWORD, "correct-horse-battery")).isEqualTo(429);
  }
}
