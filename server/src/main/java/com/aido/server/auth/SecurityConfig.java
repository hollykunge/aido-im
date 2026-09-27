package com.aido.server.auth;

import com.aido.server.realtime.RealtimeRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 登录鉴权：服务端会话 + HttpOnly Cookie（会话存在 PostgreSQL，见 Spring Session JDBC）。
 *
 * <ul>
 *   <li>前端是同源的单页应用，用会话 Cookie 而不是把令牌放进 JS：XSS 拿不到凭证，也不用自己管刷新。</li>
 *   <li>CSRF 用 Spring Security 的 SPA 模式：服务端下发可读的 XSRF-TOKEN Cookie，前端在写请求里带 X-XSRF-TOKEN 头。</li>
 *   <li>登录成功时更换会话 id 和 CSRF 令牌，防会话固定攻击。</li>
 *   <li>未登录访问接口返回 401、被拒返回 403，都是 Problem Details JSON，不做页面重定向。</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    // 默认 bcrypt，哈希带 {算法} 前缀，以后可以平滑升级算法
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  AuthenticationManager authenticationManager(AccountDetailsService accounts, PasswordEncoder encoder) {
    var provider = new DaoAuthenticationProvider(accounts);
    provider.setPasswordEncoder(encoder);
    provider.setUserDetailsPasswordService(accounts);
    return new ProviderManager(provider);
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfTokenRepository() {
    // 前端要读这个 Cookie 放进请求头，所以不能 HttpOnly
    return CookieCsrfTokenRepository.withHttpOnlyFalse();
  }

  /** 登录成功时执行：换会话 id（防会话固定）、换 CSRF 令牌。 */
  @Bean
  SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
    return new CompositeSessionAuthenticationStrategy(
        List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, SecurityContextRepository contexts, CsrfTokenRepository csrf, JsonMapper json,
      RealtimeRegistry realtime) throws Exception {
    http
        .authorizeHttpRequests(a -> a
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            // WebSocket 握手用一次性票据鉴权（票据只发给已登录的会话），见 TicketHandshakeInterceptor
            .requestMatchers("/api/realtime").permitAll()
            .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().denyAll())
        .securityContext(c -> c.securityContextRepository(contexts))
        .csrf(c -> c.spa().csrfTokenRepository(csrf))
        // 每个响应都带上 CSRF Cookie，前端第一次请求后就能拿到（包括登录前）
        .addFilterAfter(csrfCookieFilter(), BasicAuthenticationFilter.class)
        .cors(Customizer.withDefaults())
        .exceptionHandling(e -> e
            .authenticationEntryPoint(problem(json, HttpStatus.UNAUTHORIZED, "请先登录"))
            .accessDeniedHandler(problemDenied(json)))
        .logout(l -> l
            .logoutUrl("/api/auth/logout")
            // 退出时断开这个会话的 WebSocket，推送随之停止
            .addLogoutHandler((req, res, auth) -> {
              var session = req.getSession(false);
              if (session != null) realtime.closeHttpSession(session.getId());
            })
            .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.NO_CONTENT.value()))
            .deleteCookies("AIDO_SESSION"))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable);
    return http.build();
  }

  private static Filter csrfCookieFilter() {
    return (request, response, chain) -> {
      if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) token.getToken();
      chain.doFilter(request, response);
    };
  }

  private static AuthenticationEntryPoint problem(JsonMapper json, HttpStatus status, String detail) {
    return (req, res, ex) -> write(json, req, res, status, detail);
  }

  private static AccessDeniedHandler problemDenied(JsonMapper json) {
    return (req, res, ex) -> write(json, req, res, HttpStatus.FORBIDDEN, "请求被拒绝，请刷新页面后重试");
  }

  private static void write(
      JsonMapper json, HttpServletRequest req, HttpServletResponse res, HttpStatus status, String detail)
      throws IOException {
    var body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setInstance(URI.create(req.getRequestURI()));
    res.setStatus(status.value());
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    res.setCharacterEncoding(StandardCharsets.UTF_8.name());
    res.getWriter().write(json.writeValueAsString(body));
  }
}
