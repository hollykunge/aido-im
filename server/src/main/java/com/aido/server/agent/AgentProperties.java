package com.aido.server.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled 关掉后小A相关接口返回 503，前端隐藏 AI 元素，基础功能照常使用
 */
@ConfigurationProperties("aido.agent")
public record AgentProperties(String name, String tagline, Boolean enabled) {

  public AgentProperties {
    if (name == null) name = "小A";
    if (tagline == null) tagline = "你的个人工作助理";
    if (enabled == null) enabled = true;
  }
}
