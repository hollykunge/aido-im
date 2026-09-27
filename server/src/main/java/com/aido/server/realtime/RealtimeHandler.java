package com.aido.server.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 推送通道只做服务端 → 客户端；客户端发来的消息只有 pong（回应心跳），其余一律忽略。
 * 业务写操作仍然走 HTTP 接口，便于鉴权、校验和重试。
 */
@Component
public class RealtimeHandler extends TextWebSocketHandler {

  static final String ATTR_USER = "aido.userId";
  static final String ATTR_CLIENT = "aido.clientId";
  static final String ATTR_HTTP_SESSION = "aido.httpSessionId";

  private final RealtimeRegistry registry;

  public RealtimeHandler(RealtimeRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    var userId = (String) session.getAttributes().get(ATTR_USER);
    var clientId = (String) session.getAttributes().get(ATTR_CLIENT);
    var httpSessionId = (String) session.getAttributes().get(ATTR_HTTP_SESSION);
    registry.register(session, userId, clientId, httpSessionId);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // 任何来自客户端的消息都说明连接还活着
    registry.touch(session);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    registry.unregister(session);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    registry.unregister(session);
  }
}
