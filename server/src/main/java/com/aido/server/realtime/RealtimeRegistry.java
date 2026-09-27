package com.aido.server.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在线连接表：按用户登记连接，负责推送、心跳和清理死连接。
 * 单实例部署；要横向扩容时，在 {@link RealtimeDispatcher} 前面加一层跨实例广播（Redis Pub/Sub 或
 * PostgreSQL LISTEN/NOTIFY），每个实例仍只推给自己持有的连接，这里不用改。
 */
@Component
public class RealtimeRegistry {

  private static final Logger log = LoggerFactory.getLogger(RealtimeRegistry.class);

  /** 推给客户端的消息：{"type": "...", "data": {...}} */
  public record Envelope(String type, Object data) {}

  private final Map<String, Set<RealtimeConnection>> byUser = new ConcurrentHashMap<>();
  private final Map<String, RealtimeConnection> bySession = new ConcurrentHashMap<>();
  private final RealtimeProperties props;
  private final JsonMapper json;
  private final Clock clock;
  private final SessionRepository<? extends Session> httpSessions;

  public RealtimeRegistry(
      RealtimeProperties props, JsonMapper json, Clock clock, MeterRegistry meters,
      SessionRepository<? extends Session> httpSessions) {
    this.props = props;
    this.json = json;
    this.clock = clock;
    this.httpSessions = httpSessions;
    Gauge.builder("aido.realtime.connections", bySession, Map::size)
        .description("当前在线的 WebSocket 连接数")
        .register(meters);
  }

  void register(WebSocketSession session, String userId, String clientId, String httpSessionId) {
    var conn = new RealtimeConnection(session, userId, clientId, httpSessionId, props.sendQueueLimit(), clock.instant());
    var conns = byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
    conns.add(conn);
    bySession.put(session.getId(), conn);
    deliver(conn, serialize("hello", Map.of("userId", userId)));
    // 超过每人上限时，踢掉最久没动静的那条（客户端收到 4009 不会自动重连）
    if (conns.size() > props.maxConnectionsPerUser()) {
      conns.stream().min((a, b) -> a.lastSeen().compareTo(b.lastSeen()))
          .ifPresent(oldest -> oldest.close(RealtimeConnection.TOO_MANY));
    }
  }

  void unregister(WebSocketSession session) {
    var conn = bySession.remove(session.getId());
    if (conn == null) return;
    conn.stopWriter();
    byUser.computeIfPresent(conn.userId, (k, set) -> {
      set.remove(conn);
      return set.isEmpty() ? null : set;
    });
  }

  void touch(WebSocketSession session) {
    var conn = bySession.get(session.getId());
    if (conn != null) conn.touch(clock.instant());
  }

  /** 推给某个用户的所有连接；excludeClientId 为发起这次改动的标签页，它已经从 HTTP 响应里拿到结果了。 */
  public void send(String userId, String type, Object data, String excludeClientId) {
    var conns = byUser.get(userId);
    if (conns == null) return;
    var message = serialize(type, data);
    for (var conn : conns) {
      if (excludeClientId != null && excludeClientId.equals(conn.clientId)) continue;
      deliver(conn, message);
    }
  }

  /** 推给所有在线连接（如某人改了姓名，所有人的通讯录都要更新）。 */
  public void broadcast(String type, Object data, String excludeClientId) {
    var message = serialize(type, data);
    for (var conn : bySession.values()) {
      if (excludeClientId != null && excludeClientId.equals(conn.clientId)) continue;
      deliver(conn, message);
    }
  }

  public void send(Collection<String> userIds, String type, Object data, String excludeClientId) {
    userIds.forEach(u -> send(u, type, data, excludeClientId));
  }

  public boolean isOnline(String userId) {
    return byUser.containsKey(userId);
  }

  public int connectionCount() {
    return bySession.size();
  }

  /** 心跳：定时发 ping；超过 idleTimeout 没有任何回音的连接判定已死，断开。 */
  @Scheduled(fixedDelayString = "${aido.realtime.heartbeat:25s}")
  void heartbeat() {
    var deadline = clock.instant().minus(props.idleTimeout());
    var ping = serialize("ping", Map.of());
    for (var conn : List.copyOf(bySession.values())) {
      if (conn.lastSeen().isBefore(deadline)) {
        log.debug("连接无响应，断开 {}", conn.session.getId());
        conn.close(CloseStatus.SESSION_NOT_RELIABLE);
      } else {
        deliver(conn, ping);
      }
    }
  }

  /** 退出登录 / 会话作废：断开这个登录会话建立的所有连接，客户端回到登录页。 */
  public void closeHttpSession(String httpSessionId) {
    close(httpSessionId, RealtimeConnection.UNAUTHORIZED);
  }

  /** 登录会话换了 id：断开旧 id 上的连接，客户端会用新会话重连。 */
  public void renewHttpSession(String oldHttpSessionId) {
    close(oldHttpSessionId, RealtimeConnection.RENEWED);
  }

  private void close(String httpSessionId, CloseStatus status) {
    bySession.values().stream()
        .filter(c -> httpSessionId.equals(c.httpSessionId))
        .forEach(c -> c.close(status));
  }

  /** 登录会话过期（长时间不活动）后，断开它建立的连接。每个会话查一次，100 人在线也只是每分钟百来次主键查询。 */
  @Scheduled(fixedDelay = 60_000)
  void dropExpiredSessions() {
    bySession.values().stream()
        .map(c -> c.httpSessionId)
        .filter(Objects::nonNull)
        .distinct()
        .filter(id -> httpSessions.findById(id) == null)
        .forEach(this::closeHttpSession);
  }

  private void deliver(RealtimeConnection conn, TextMessage message) {
    if (!conn.offer(message)) {
      log.info("客户端消费过慢，断开 user={} session={}", conn.userId, conn.session.getId());
      conn.close(RealtimeConnection.SLOW_CONSUMER);
    }
  }

  private TextMessage serialize(String type, Object data) {
    return new TextMessage(json.writeValueAsString(new Envelope(type, data)));
  }

  /** 停机时通知客户端「服务器要走了」，它们会带退避地重连到新实例。 */
  @PreDestroy
  void shutdown() {
    bySession.values().forEach(c -> c.close(CloseStatus.GOING_AWAY));
  }
}
