package com.aido.server.auth;

import com.aido.server.common.CurrentUser;
import com.aido.server.user.UserDto;
import com.aido.server.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 登录、修改密码。退出由 Spring Security 处理：POST /api/auth/logout（见 SecurityConfig）。 */
@RestController
public class AuthController {

  private final AuthenticationManager authentication;
  private final SecurityContextRepository contexts;
  private final SessionAuthenticationStrategy sessionStrategy;
  private final LoginThrottle throttle;
  private final UserRepository users;
  private final PasswordService passwords;

  public AuthController(
      AuthenticationManager authentication, SecurityContextRepository contexts,
      SessionAuthenticationStrategy sessionStrategy, LoginThrottle throttle, UserRepository users,
      PasswordService passwords) {
    this.authentication = authentication;
    this.contexts = contexts;
    this.sessionStrategy = sessionStrategy;
    this.throttle = throttle;
    this.users = users;
    this.passwords = passwords;
  }

  public record Credentials(@NotBlank @Size(max = 64) String login, @NotBlank @Size(max = 128) String password) {}

  /** 登录成功返回当前用户，并下发会话 Cookie；失败统一提示「账号或密码错误」，不透露账号是否存在。 */
  @PostMapping("/api/auth/login")
  public ResponseEntity<?> login(
      @RequestBody @Valid Credentials body, HttpServletRequest request, HttpServletResponse response) {
    var ip = request.getRemoteAddr();
    var wait = throttle.blocked(body.login(), ip);
    if (wait.isPresent()) {
      var seconds = wait.get().toSeconds();
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
          .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
              "登录失败次数太多，请 " + Math.max(1, (seconds + 59) / 60) + " 分钟后再试"));
    }
    try {
      var auth = authentication.authenticate(
          UsernamePasswordAuthenticationToken.unauthenticated(body.login().strip(), body.password()));
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(auth);
      SecurityContextHolder.setContext(context);
      sessionStrategy.onAuthentication(auth, request, response);
      contexts.saveContext(context, request, response);
      // 登录时换了 CSRF 令牌；立即下发新的 Cookie，前端登录后的第一个写请求就能用
      if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) token.getToken();
      throttle.recordSuccess(body.login());
      UserDto me = users.findById(auth.getName()).orElseThrow();
      return ResponseEntity.ok(me);
    } catch (DisabledException e) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "账号已停用，请联系管理员"));
    } catch (AuthenticationException e) {
      throttle.recordFailure(body.login(), ip);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "账号或密码错误"));
    }
  }

  public record PasswordChange(@NotBlank @Size(max = 128) String currentPassword, @NotBlank @Size(max = 128) String newPassword) {}

  /**
   * 修改自己的密码：要验证当前密码。成功后其他设备上的登录全部失效，当前会话保留。
   * 当前密码错误返回 400 而不是 401——401 在前端表示「会话没了」，会把人踢回登录页。
   */
  @PostMapping("/api/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(
      @CurrentUser String me, @RequestBody @Valid PasswordChange body, HttpServletRequest request) {
    passwords.change(me, body.currentPassword(), body.newPassword(), request);
  }
}
