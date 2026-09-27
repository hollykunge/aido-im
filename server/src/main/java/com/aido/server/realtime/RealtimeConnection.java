package com.aido.server.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 一条 WebSocket 连接。
 * 发送走「有界队列 + 专属写线程（虚拟线程）」：推送方只是入队，从不因为某个慢客户端被卡住；
 * 同一连接的消息由唯一的写线程按入队顺序发出，保证顺序。
 * 队列满说明客户端消费不过来，直接断开——客户端会重连并重新拉取数据，比无限堆积内存更安全。
 */
final class RealtimeConnection {

  private static final Logger log = LoggerFactory.getLogger(RealtimeConnection.class);

  /** 应用自定义关闭码：客户端太慢被踢。4000–4999 留给应用使用。 */
  static final CloseStatus SLOW_CONSUMER = new CloseStatus(4008, "slow consumer");
  /** 同一用户连接数超限，最旧的一条被挤掉。 */
  static final CloseStatus TOO_MANY = new CloseStatus(4009, "too many connections");
  /** 登录会话已失效（退出登录或过期）。客户端收到后回到登录页，不重连。 */
  static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "session ended");
  /** 登录会话换了 id（如改密码后）。客户端按普通断线处理：用新会话换票据重连。 */
  static final CloseStatus RENEWED = new CloseStatus(4012, "session renewed");

  private static final TextMessage POISON = new TextMessage("");

  final WebSocketSession session;
  final String userId;
  final String clientId;
  final String httpSessionId;
  private final BlockingQueue<TextMessage> outbox;
  private final Thread writer;
  private volatile Instant lastSeen;

  RealtimeConnection(
      WebSocketSession session, String userId, String clientId, String httpSessionId, int queueLimit, Instant now) {
    this.session = session;
    this.userId = userId;
    this.clientId = clientId;
    this.httpSessionId = httpSessionId;
    this.outbox = new ArrayBlockingQueue<>(queueLimit);
    this.lastSeen = now;
    this.writer = Thread.ofVirtual().name("ws-writer-" + userId + "-" + session.getId()).start(this::drain);
  }

  /** 入队待发；返回 false 表示队列已满（慢客户端），调用方应断开它。 */
  boolean offer(TextMessage message) {
    return session.isOpen() && outbox.offer(message);
  }

  void touch(Instant now) {
    lastSeen = now;
  }

  Instant lastSeen() {
    return lastSeen;
  }

  void close(CloseStatus status) {
    try {
      if (session.isOpen()) session.close(status);
    } catch (IOException e) {
      log.debug("关闭连接失败 {}", session.getId(), e);
    }
  }

  /** 连接关闭后调用：唤醒并结束写线程。 */
  void stopWriter() {
    outbox.clear();
    outbox.offer(POISON);
  }

  private void drain() {
    try {
      while (true) {
        var message = outbox.take();
        if (message == POISON || !session.isOpen()) return;
        session.sendMessage(message);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | IllegalStateException e) {
      log.debug("发送失败，关闭连接 {}", session.getId(), e);
      close(CloseStatus.SESSION_NOT_RELIABLE);
    }
  }
}
