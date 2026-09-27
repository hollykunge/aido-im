package com.aido.server.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/** 把当前登录用户的 id 注入到标了 {@link CurrentUser} 的参数。登录后安全上下文里的用户名就是用户 id（见 AccountDetailsService）。 */
@Component
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(CurrentUser.class) && parameter.getParameterType() == String.class;
  }

  @Override
  public String resolveArgument(
      MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request, WebDataBinderFactory binder) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    // 安全配置已经拦下了未登录的请求，这里是兜底
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
    return auth.getName();
  }
}
