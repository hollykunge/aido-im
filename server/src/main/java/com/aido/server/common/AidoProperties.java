package com.aido.server.common;

import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param zone        业务时区：「今天」「明天」、日程和 TODO 的日期都按它计算
 * @param corsOrigins 允许跨域访问的前端地址（同时用于 WebSocket 握手的来源校验）
 */
@ConfigurationProperties("aido")
public record AidoProperties(ZoneId zone, List<String> corsOrigins) {

  public AidoProperties {
    if (zone == null) zone = ZoneId.of("Asia/Shanghai");
    if (corsOrigins == null) corsOrigins = List.of();
  }
}
