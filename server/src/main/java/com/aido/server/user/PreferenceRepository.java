package com.aido.server.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class PreferenceRepository {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public PreferenceRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public record Preferences(String theme, JsonNode agentLook, boolean memoryLearning) {}

  public Preferences get(String userId) {
    // 首次访问时补一行默认偏好
    jdbc.sql("INSERT INTO user_preference (user_id) VALUES (?) ON CONFLICT DO NOTHING").param(userId).update();
    return jdbc.sql("SELECT theme, agent_look::text AS look, memory_learning FROM user_preference WHERE user_id = ?")
        .param(userId)
        .query((rs, i) -> new Preferences(
            rs.getString("theme"), json.readTree(rs.getString("look")), rs.getBoolean("memory_learning")))
        .single();
  }

  /** 只更新传了值的字段。 */
  public Preferences update(String userId, String theme, JsonNode agentLook, Boolean memoryLearning) {
    get(userId);
    jdbc.sql("""
        UPDATE user_preference SET
          theme = coalesce(:theme, theme),
          agent_look = coalesce(CAST(:look AS jsonb), agent_look),
          memory_learning = coalesce(:learning, memory_learning),
          updated_at = now()
        WHERE user_id = :id
        """)
        .param("theme", theme)
        .param("look", agentLook == null ? null : json.writeValueAsString(agentLook))
        .param("learning", memoryLearning)
        .param("id", userId)
        .update();
    return get(userId);
  }
}
