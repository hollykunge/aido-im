package com.aido.server.agent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 小A对话流。返回给前端的每一项是 payload 的字段再加上 id / type / createdAt。 */
@Repository
public class AgentFeedRepository {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public AgentFeedRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private static final String SELECT = "SELECT id, type, payload::text AS payload, created_at FROM agent_feed_item";

  /** 最近 limit 条，按时间正序。 */
  public List<ObjectNode> recent(String me, int limit) {
    return jdbc.sql(SELECT + " WHERE owner_id = ? ORDER BY id DESC LIMIT ?")
        .params(me, limit)
        .query((rs, i) -> toItem(rs.getLong("id"), rs.getString("type"), rs.getString("payload"),
            rs.getObject("created_at", OffsetDateTime.class)))
        .list()
        .reversed();
  }

  public Optional<ObjectNode> find(String me, long id) {
    return jdbc.sql(SELECT + " WHERE owner_id = ? AND id = ?")
        .params(me, id)
        .query((rs, i) -> toItem(rs.getLong("id"), rs.getString("type"), rs.getString("payload"),
            rs.getObject("created_at", OffsetDateTime.class)))
        .optional();
  }

  public ObjectNode insert(String me, String type, ObjectNode payload) {
    return jdbc.sql("""
        INSERT INTO agent_feed_item (owner_id, type, payload) VALUES (?, ?, CAST(? AS jsonb))
        RETURNING id, type, payload::text AS payload, created_at
        """)
        .params(me, type, json.writeValueAsString(payload))
        .query((rs, i) -> toItem(rs.getLong("id"), rs.getString("type"), rs.getString("payload"),
            rs.getObject("created_at", OffsetDateTime.class)))
        .single();
  }

  /** 合并更新 payload 的部分字段（如草稿状态）。 */
  public void mergePayload(String me, long id, ObjectNode patch) {
    jdbc.sql("UPDATE agent_feed_item SET payload = payload || CAST(? AS jsonb) WHERE owner_id = ? AND id = ?")
        .params(json.writeValueAsString(patch), me, id)
        .update();
  }

  private ObjectNode toItem(long id, String type, String payload, OffsetDateTime createdAt) {
    var item = json.createObjectNode();
    item.put("id", id);
    item.put("type", type);
    item.put("createdAt", createdAt.toString());
    item.setAll((ObjectNode) json.readTree(payload));
    return item;
  }

  public List<String> quickPrompts(String me) {
    return jdbc.sql("SELECT body FROM agent_quick_prompt WHERE owner_id = ? ORDER BY position, id")
        .param(me)
        .query(String.class)
        .list();
  }

  public record Capability(String key, String title, String description, boolean enabled) {}

  public List<Capability> capabilities(String me) {
    return jdbc.sql("SELECT key, title, description, enabled FROM agent_capability WHERE owner_id = ? ORDER BY position")
        .param(me)
        .query(Capability.class)
        .list();
  }

  public Optional<Capability> setCapability(String me, String key, boolean enabled) {
    return jdbc.sql("""
        UPDATE agent_capability SET enabled = ? WHERE owner_id = ? AND key = ?
        RETURNING key, title, description, enabled
        """)
        .params(enabled, me, key)
        .query(Capability.class)
        .optional();
  }
}
