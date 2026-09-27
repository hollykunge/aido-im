package com.aido.server.realtime;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 当前 HTTP 请求来自哪个标签页（X-Client-Id）。推送时跳过它，避免把它自己刚做的改动再推回去。 */
public final class ClientIds {

  public static final String HEADER = "X-Client-Id";

  private ClientIds() {}

  public static String current() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
        ? attrs.getRequest().getHeader(HEADER)
        : null;
  }
}
