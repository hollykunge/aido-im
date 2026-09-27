package com.aido.server.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param window                时间窗
 * @param maxFailuresPerAccount 同一账号在时间窗内最多失败次数，超过后暂时拒绝该账号登录
 * @param maxFailuresPerIp      同一 IP 在时间窗内最多失败次数（防止换着账号撞库）
 */
@ConfigurationProperties("aido.login-throttle")
public record LoginThrottleProperties(Duration window, Integer maxFailuresPerAccount, Integer maxFailuresPerIp) {

  public LoginThrottleProperties {
    if (window == null) window = Duration.ofMinutes(15);
    if (maxFailuresPerAccount == null) maxFailuresPerAccount = 5;
    if (maxFailuresPerIp == null) maxFailuresPerIp = 20;
  }
}
