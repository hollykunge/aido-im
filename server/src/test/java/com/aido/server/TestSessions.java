package com.aido.server;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 测试用的「浏览器」：带 Cookie 存储，像前端一样先拿 CSRF Cookie，再登录，之后的写请求自动带 X-XSRF-TOKEN。
 * 错误状态不抛异常，由测试自己断言状态码。
 */
final class TestSessions {

  static final String DEMO_PASSWORD = "aido1234";

  private final int port;

  TestSessions(int port) {
    this.port = port;
  }

  /** 一个独立的浏览器会话（各自的 Cookie）。clientId 模拟标签页 id，可为空。 */
  final class Browser {
    final CookieManager cookies = new CookieManager();
    final RestClient api;

    Browser(String clientId) {
      var http = HttpClient.newBuilder().cookieHandler(cookies).build();
      var builder = RestClient.builder()
          .baseUrl("http://localhost:" + port + "/api")
          .requestFactory(new JdkClientHttpRequestFactory(http))
          .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
          .requestInterceptor((req, body, exec) -> {
            if (req.getMethod() != HttpMethod.GET) {
              var token = cookie("XSRF-TOKEN");
              if (token != null) req.getHeaders().set("X-XSRF-TOKEN", token);
            }
            return exec.execute(req, body);
          });
      if (clientId != null) builder.defaultHeader("X-Client-Id", clientId);
      this.api = builder.build();
    }

    String cookie(String name) {
      return cookies.getCookieStore().get(URI.create("http://localhost:" + port)).stream()
          .filter(c -> c.getName().equals(name))
          .map(HttpCookie::getValue)
          .findFirst()
          .orElse(null);
    }

    /** 登录，返回状态码。先发一个 GET 拿到 CSRF Cookie（和前端启动时一样）。 */
    int login(String login, String password) {
      api.get().uri("/me").retrieve().toBodilessEntity();
      return api.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("login", login, "password", password))
          .retrieve().toBodilessEntity().getStatusCode().value();
    }
  }

  /** 演示账号的登录名（用户 id → 账号）。 */
  static final Map<String, String> LOGINS = Map.of(
      "me", "linzhou", "zy", "zhouyuan", "csq", "chensiqi", "wl", "wanglei",
      "zyf", "zhaoyifan", "ln", "linan", "sy", "sunyue", "hj", "hejing");

  /** 以某个演示用户登录的浏览器。 */
  Browser as(String userId, String clientId) {
    var b = new Browser(clientId);
    var status = b.login(LOGINS.getOrDefault(userId, userId), DEMO_PASSWORD);
    if (status != 200) throw new IllegalStateException(userId + " 登录失败：" + status);
    return b;
  }

  Browser as(String userId) {
    return as(userId, null);
  }
}
