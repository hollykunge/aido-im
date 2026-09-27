package com.aido.server.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param heartbeat             服务端发 ping 的间隔
 * @param idleTimeout           这么久没收到客户端任何消息（包括 pong）就判定连接已死并断开
 * @param sendQueueLimit        每条连接最多积压的待发消息；超过说明客户端太慢，断开让它重连后重新同步
 * @param maxConnectionsPerUser 同一用户最多同时在线的连接数（多个标签页、多台设备）
 */
@ConfigurationProperties("aido.realtime")
public record RealtimeProperties(
    Duration heartbeat, Duration idleTimeout, Integer sendQueueLimit, Integer maxConnectionsPerUser) {

  public RealtimeProperties {
    if (heartbeat == null) heartbeat = Duration.ofSeconds(25);
    if (idleTimeout == null) idleTimeout = Duration.ofSeconds(60);
    if (sendQueueLimit == null) sendQueueLimit = 256;
    if (maxConnectionsPerUser == null) maxConnectionsPerUser = 8;
  }
}
