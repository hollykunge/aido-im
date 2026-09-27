package com.aido.server.realtime;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * WebSocket 握手用的一次性票据。
 * 已登录的会话先用普通 HTTP 请求（带会话 Cookie 和 CSRF 头）换一张 30 秒内有效、只能用一次的票据，再拿它握手。
 * 比直接靠 Cookie 握手多一层：别的网站即使诱导浏览器发起 WebSocket 连接（CSWSH），也拿不到票据。
 * 票据记着签发它的登录会话，退出登录或会话过期时据此断开对应的连接。
 */
@Component
public class RealtimeTickets {

  static final Duration TTL = Duration.ofSeconds(30);

  /** 核销结果：用户和签发票据的登录会话。 */
  public record Holder(String userId, String httpSessionId) {}

  private record Ticket(Holder holder, Instant expiresAt) {}

  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;

  public RealtimeTickets(Clock clock) {
    this.clock = clock;
  }

  public String issue(String userId, String httpSessionId) {
    var bytes = new byte[32];
    random.nextBytes(bytes);
    var ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tickets.put(ticket, new Ticket(new Holder(userId, httpSessionId), clock.instant().plus(TTL)));
    return ticket;
  }

  /** 核销票据：有效则返回持有人，并立即作废。 */
  public Optional<Holder> redeem(String ticket) {
    if (ticket == null) return Optional.empty();
    var t = tickets.remove(ticket);
    if (t == null || t.expiresAt().isBefore(clock.instant())) return Optional.empty();
    return Optional.of(t.holder());
  }

  @Scheduled(fixedDelay = 60_000)
  void purgeExpired() {
    var now = clock.instant();
    tickets.values().removeIf(t -> t.expiresAt().isBefore(now));
  }
}
