package com.aido.server.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流（滑动时间窗）：同一账号、同一 IP 失败太多次就暂时拒绝，挡住暴力破解和撞库。
 * 计数在内存里，单实例足够；多实例部署时换成 Redis 等共享存储。
 */
@Component
public class LoginThrottle {

  private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
  private final LoginThrottleProperties props;
  private final Clock clock;

  public LoginThrottle(LoginThrottleProperties props, Clock clock) {
    this.props = props;
    this.clock = clock;
  }

  /** 被限流时返回还需等待多久；未被限流返回空。 */
  public Optional<Duration> blocked(String login, String ip) {
    var account = retryAfter(accountKey(login), props.maxFailuresPerAccount());
    var address = retryAfter(ipKey(ip), props.maxFailuresPerIp());
    if (account.isEmpty()) return address;
    if (address.isEmpty()) return account;
    return Optional.of(account.get().compareTo(address.get()) > 0 ? account.get() : address.get());
  }

  public void recordFailure(String login, String ip) {
    var now = clock.instant();
    failures.computeIfAbsent(accountKey(login), k -> new ConcurrentLinkedDeque<>()).addLast(now);
    failures.computeIfAbsent(ipKey(ip), k -> new ConcurrentLinkedDeque<>()).addLast(now);
  }

  /** 登录成功后清掉该账号的失败记录（IP 的保留，防止用一个真账号给撞库“洗白”）。 */
  public void recordSuccess(String login) {
    failures.remove(accountKey(login));
  }

  private Optional<Duration> retryAfter(String key, int max) {
    var list = failures.get(key);
    if (list == null) return Optional.empty();
    var windowStart = clock.instant().minus(props.window());
    list.removeIf(t -> t.isBefore(windowStart));
    if (list.size() < max) return Optional.empty();
    // 等到时间窗内最早的那次失败过期，才能再试
    return Optional.of(Duration.between(windowStart, list.peekFirst()).plusSeconds(1));
  }

  private static String accountKey(String login) {
    return "acct:" + login.strip().toLowerCase(Locale.ROOT);
  }

  private static String ipKey(String ip) {
    return "ip:" + ip;
  }

  @Scheduled(fixedDelay = 300_000)
  void purge() {
    var windowStart = clock.instant().minus(props.window());
    failures.values().forEach(list -> list.removeIf(t -> t.isBefore(windowStart)));
    failures.values().removeIf(Deque::isEmpty);
  }
}
