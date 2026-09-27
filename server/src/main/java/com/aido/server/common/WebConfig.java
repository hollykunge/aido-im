package com.aido.server.common;

import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final CurrentUserResolver currentUserResolver;
  private final AidoProperties props;

  public WebConfig(CurrentUserResolver currentUserResolver, AidoProperties props) {
    this.currentUserResolver = currentUserResolver;
    this.props = props;
  }

  /** 业务时钟：统一走它取「现在」，测试里可以替换成固定时间。 */
  @Bean
  Clock clock() {
    return Clock.system(props.zone());
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(currentUserResolver);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (props.corsOrigins().isEmpty()) return;
    registry.addMapping("/api/**")
        .allowedOrigins(props.corsOrigins().toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
        .allowedHeaders("*")
        // 跨域部署前端时要带上会话 Cookie
        .allowCredentials(true);
  }
}
