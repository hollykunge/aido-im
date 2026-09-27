package com.aido.server.auth;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 按登录账号（不区分大小写）查用户。返回的 UserDetails 用户名是用户 id 而不是登录账号：
 * 之后安全上下文里的 name 直接就是 id，业务代码拿来就能用，改登录账号也不影响已有会话。
 * 同时实现密码哈希升级：旧算法或低强度的哈希在用户下次登录成功时自动换成当前默认算法。
 */
@Service
public class AccountDetailsService implements UserDetailsService, UserDetailsPasswordService {

  private final JdbcClient jdbc;

  public AccountDetailsService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private record Account(String id, String passwordHash, boolean disabled) {}

  @Override
  public UserDetails loadUserByUsername(String login) {
    var account = jdbc.sql("""
        SELECT id, password_hash, disabled FROM app_user
        WHERE lower(login) = lower(?) AND password_hash IS NOT NULL
        """)
        .param(login.strip())
        .query(Account.class)
        .optional()
        .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    return User.withUsername(account.id())
        .password(account.passwordHash())
        .disabled(account.disabled())
        .authorities(List.of())
        .build();
  }

  @Override
  public UserDetails updatePassword(UserDetails user, String newPasswordHash) {
    jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?").params(newPasswordHash, user.getUsername()).update();
    return User.withUserDetails(user).password(newPasswordHash).build();
  }
}
