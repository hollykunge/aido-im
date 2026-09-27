package com.aido.server.realtime;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** 握手时核销票据确定用户；没有有效票据直接拒绝（401），不升级为 WebSocket。 */
@Component
public class TicketHandshakeInterceptor implements HandshakeInterceptor {

  private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

  private final RealtimeTickets tickets;

  public TicketHandshakeInterceptor(RealtimeTickets tickets) {
    this.tickets = tickets;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attrs) {
    var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
    var holder = tickets.redeem(query.getFirst("ticket"));
    if (holder.isEmpty()) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    var clientId = query.getFirst("client");
    attrs.put(RealtimeHandler.ATTR_USER, holder.get().userId());
    attrs.put(RealtimeHandler.ATTR_HTTP_SESSION, holder.get().httpSessionId());
    attrs.put(RealtimeHandler.ATTR_CLIENT, clientId != null && CLIENT_ID.matcher(clientId).matches() ? clientId : null);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}
}
