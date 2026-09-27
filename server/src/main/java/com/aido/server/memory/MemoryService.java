package com.aido.server.memory;

import com.aido.server.common.NotFoundException;
import com.aido.server.realtime.RealtimeEvents;
import com.aido.server.user.PreferenceRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 记忆：小雀记住的关于你的画像、具体记忆和学习来源。仅本人可见；每次改动都把整页快照推给本人的其他标签页。 */
@Service
public class MemoryService {

  private final JdbcClient jdbc;
  private final PreferenceRepository preferences;
  private final RealtimeEvents events;

  public MemoryService(JdbcClient jdbc, PreferenceRepository preferences, RealtimeEvents events) {
    this.jdbc = jdbc;
    this.preferences = preferences;
    this.events = events;
  }

  public record Tag(long id, String tag) {}

  public record ProfileGroup(String group, List<Tag> tags) {}

  public record Item(long id, String text, String source, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

  public record Source(String key, String label, String description, boolean enabled) {}

  /** @param learning 是否允许小雀从日常工作中学习 */
  public record Memory(boolean learning, List<ProfileGroup> profile, List<Item> items, List<Source> sources) {}

  public Memory snapshot(String me) {
    return new Memory(preferences.get(me).memoryLearning(), profile(me), items(me), sources(me));
  }

  // —— 画像标签 ——

  private List<ProfileGroup> profile(String me) {
    Map<String, List<Tag>> groups = new LinkedHashMap<>();
    jdbc.sql("""
        SELECT id, group_name, tag FROM memory_profile_tag WHERE owner_id = ?
        ORDER BY min(id) OVER (PARTITION BY group_name), position, id
        """)
        .param(me)
        .query(rs -> {
          groups.computeIfAbsent(rs.getString("group_name"), g -> new ArrayList<>())
              .add(new Tag(rs.getLong("id"), rs.getString("tag")));
        });
    return groups.entrySet().stream().map(e -> new ProfileGroup(e.getKey(), e.getValue())).toList();
  }

  /** 同一分组里已有的标签不重复创建，返回已有那一个。 */
  @Transactional
  public Tag addTag(String me, String group, String tag) {
    long id = jdbc.sql("""
        INSERT INTO memory_profile_tag (owner_id, group_name, tag, position)
        SELECT :me, :group, :tag, coalesce(max(position) + 1, 0)
        FROM memory_profile_tag WHERE owner_id = :me AND group_name = :group
        ON CONFLICT (owner_id, group_name, tag) DO UPDATE SET tag = EXCLUDED.tag
        RETURNING id
        """)
        .param("me", me)
        .param("group", group)
        .param("tag", tag)
        .query(Long.class)
        .single();
    events.memoryChanged(me);
    return new Tag(id, tag);
  }

  @Transactional
  public void removeTag(String me, long id) {
    if (jdbc.sql("DELETE FROM memory_profile_tag WHERE owner_id = ? AND id = ?").params(me, id).update() == 0) {
      throw new NotFoundException("画像标签", id);
    }
    events.memoryChanged(me);
  }

  // —— 具体记忆 ——

  private static final String ITEM_SELECT =
      "SELECT id, body AS text, source, created_at, updated_at FROM memory_item WHERE owner_id = :me";

  private List<Item> items(String me) {
    return jdbc.sql(ITEM_SELECT + " ORDER BY created_at DESC, id DESC").param("me", me).query(Item.class).list();
  }

  private Item item(String me, long id) {
    return jdbc.sql(ITEM_SELECT + " AND id = :id").param("me", me).param("id", id).query(Item.class).single();
  }

  @Transactional
  public Item addItem(String me, String text) {
    long id = jdbc.sql("INSERT INTO memory_item (owner_id, body) VALUES (?, ?) RETURNING id")
        .params(me, text)
        .query(Long.class)
        .single();
    events.memoryChanged(me);
    return item(me, id);
  }

  @Transactional
  public Item editItem(String me, long id, String text) {
    int n = jdbc.sql("UPDATE memory_item SET body = ?, updated_at = now() WHERE owner_id = ? AND id = ?")
        .params(text, me, id)
        .update();
    if (n == 0) throw new NotFoundException("记忆", id);
    events.memoryChanged(me);
    return item(me, id);
  }

  @Transactional
  public void removeItem(String me, long id) {
    if (jdbc.sql("DELETE FROM memory_item WHERE owner_id = ? AND id = ?").params(me, id).update() == 0) {
      throw new NotFoundException("记忆", id);
    }
    events.memoryChanged(me);
  }

  // —— 学习来源 ——

  private List<Source> sources(String me) {
    return jdbc.sql("SELECT key, label, description, enabled FROM memory_source WHERE owner_id = ? ORDER BY position")
        .param(me)
        .query(Source.class)
        .list();
  }

  @Transactional
  public Source toggleSource(String me, String key, boolean enabled) {
    var source = jdbc.sql("""
        UPDATE memory_source SET enabled = ? WHERE owner_id = ? AND key = ?
        RETURNING key, label, description, enabled
        """)
        .params(enabled, me, key)
        .query(Source.class)
        .optional()
        .orElseThrow(() -> new NotFoundException("学习来源", key));
    events.memoryChanged(me);
    return source;
  }
}
