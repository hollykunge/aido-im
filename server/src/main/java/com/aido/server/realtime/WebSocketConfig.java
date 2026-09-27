package com.aido.server.realtime;

import com.aido.server.common.AidoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

  /** 客户端只会发 pong，入站消息给得很小，防止被当成大消息通道滥用。 */
  private static final int MAX_INBOUND_BYTES = 8 * 1024;

  private final RealtimeHandler handler;
  private final TicketHandshakeInterceptor tickets;
  private final AidoProperties props;
  private final RealtimeProperties realtime;

  public WebSocketConfig(
      RealtimeHandler handler, TicketHandshakeInterceptor tickets, AidoProperties props, RealtimeProperties realtime) {
    this.handler = handler;
    this.tickets = tickets;
    this.props = props;
    this.realtime = realtime;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    // 放在 /api 下，开发时和其他接口一起经 Vite 代理；不配置跨域来源时只允许同源页面连接
    registry.addHandler(handler, "/api/realtime")
        .addInterceptors(tickets)
        .setAllowedOrigins(props.corsOrigins().toArray(String[]::new));
  }

  @Bean
  ServletServerContainerFactoryBean webSocketContainer() {
    var container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(MAX_INBOUND_BYTES);
    container.setMaxBinaryMessageBufferSize(MAX_INBOUND_BYTES);
    // 容器层兜底：比心跳判定再宽一点，应用层心跳先生效
    container.setMaxSessionIdleTimeout(realtime.idleTimeout().plusSeconds(30).toMillis());
    return container;
  }
}
