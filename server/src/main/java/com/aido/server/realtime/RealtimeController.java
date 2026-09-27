package com.aido.server.realtime;

import com.aido.server.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RealtimeController {

  private final RealtimeTickets tickets;

  public RealtimeController(RealtimeTickets tickets) {
    this.tickets = tickets;
  }

  /** @param expiresIn 秒 */
  public record Ticket(String ticket, long expiresIn) {}

  /** 换一张 WebSocket 握手票据：拿到后 30 秒内连接 /api/realtime?ticket=…，只能用一次。 */
  @PostMapping("/api/realtime/ticket")
  public Ticket ticket(@CurrentUser String me, HttpServletRequest request) {
    // 能走到这里说明已登录，一定有会话
    var session = request.getSession(false).getId();
    return new Ticket(tickets.issue(me, session), RealtimeTickets.TTL.toSeconds());
  }
}
