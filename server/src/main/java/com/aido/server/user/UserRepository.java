package com.aido.server.user;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<UserDto> findAll() {
    return jdbc.sql("SELECT id, name, role, dept, color FROM app_user ORDER BY dept, name").query(UserDto.class).list();
  }

  /** 只改传了值的字段。 */
  public void updateProfile(String id, String name, String color) {
    jdbc.sql("UPDATE app_user SET name = coalesce(CAST(? AS text), name), color = coalesce(CAST(? AS text), color) WHERE id = ?")
        .params(name, color, id)
        .update();
  }

  /** 登录账号只给本人看，不放进通讯录用的 UserDto。 */
  public Optional<String> findLogin(String id) {
    return jdbc.sql("SELECT login FROM app_user WHERE id = ?").param(id).query(String.class).optional();
  }

  public Optional<UserDto> findById(String id) {
    return jdbc.sql("SELECT id, name, role, dept, color FROM app_user WHERE id = ?").param(id).query(UserDto.class).optional();
  }
}
