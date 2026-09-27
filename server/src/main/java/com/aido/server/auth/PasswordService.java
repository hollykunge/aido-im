package com.aido.server.auth;

import com.aido.server.realtime.RealtimeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 修改密码。规则参照 NIST SP 800-63B：看长度、拦常见弱密码，不强制「必须含符号」之类的组合规则。
 * 改成功后结束这个用户的其他所有登录会话（及其 WebSocket），当前会话保留但更换会话 id。
 */
@Service
public class PasswordService {

  static final int MIN_LENGTH = 8;
  /** bcrypt 只取前 72 字节，更长的部分会被忽略，所以直接拒绝。 */
  static final int MAX_BYTES = 72;

  private static final Set<String> COMMON = Set.of(
      "12345678", "123456789", "1234567890", "87654321", "11111111", "00000000", "88888888",
      "password", "password1", "passw0rd", "qwertyui", "qwerty123", "1qaz2wsx", "abc12345", "abcd1234",
      "a1234567", "iloveyou", "woaini1314", "admin123", "aido1234");

  private final JdbcClient jdbc;
  private final PasswordEncoder encoder;
  private final LoginThrottle throttle;
  private final FindByIndexNameSessionRepository<? extends Session> sessions;
  private final RealtimeRegistry realtime;

  public PasswordService(
      JdbcClient jdbc, PasswordEncoder encoder, LoginThrottle throttle,
      FindByIndexNameSessionRepository<? extends Session> sessions, RealtimeRegistry realtime) {
    this.jdbc = jdbc;
    this.encoder = encoder;
    this.throttle = throttle;
    this.sessions = sessions;
    this.realtime = realtime;
  }

  private record Account(String login, String passwordHash) {}

  public void change(String userId, String current, String next, HttpServletRequest request) {
    var account = jdbc.sql("SELECT login, password_hash FROM app_user WHERE id = ?")
        .param(userId)
        .query(Account.class)
        .single();
    var ip = request.getRemoteAddr();

    // 验证当前密码；失败和登录失败一起计数，防止借这个接口暴力试密码
    if (throttle.blocked(account.login(), ip).isPresent()) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "尝试次数太多，请稍后再试");
    }
    if (!encoder.matches(current, account.passwordHash())) {
      throttle.recordFailure(account.login(), ip);
      throw new IllegalArgumentException("当前密码不正确");
    }
    validate(next, current, account.login());

    jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?").params(encoder.encode(next), userId).update();
    throttle.recordSuccess(account.login());

    // 别的设备、浏览器上的登录全部作废（可能是被盗用的），推送连接一并断开
    var session = request.getSession(false);
    var keep = session == null ? null : session.getId();
    sessions.findByPrincipalName(userId).keySet().stream()
        .filter(id -> !id.equals(keep))
        .forEach(id -> {
          sessions.deleteById(id);
          realtime.closeHttpSession(id);
        });
    // 当前会话继续用，但换个会话 id；挂在旧 id 上的 WebSocket 断开，客户端会带新会话重连
    if (session != null) {
      request.changeSessionId();
      realtime.renewHttpSession(keep);
    }
  }

  static void validate(String next, String current, String login) {
    if (next.length() < MIN_LENGTH) throw new IllegalArgumentException("新密码至少 " + MIN_LENGTH + " 位");
    if (next.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("新密码太长了");
    if (next.equals(current)) throw new IllegalArgumentException("新密码不能和当前密码相同");
    var lower = next.toLowerCase(Locale.ROOT);
    if (login != null && lower.contains(login.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("新密码不能包含账号名");
    }
    if (COMMON.contains(lower) || next.chars().distinct().count() == 1) {
      throw new IllegalArgumentException("这个密码太常见了，换一个更难猜的");
    }
  }
}
