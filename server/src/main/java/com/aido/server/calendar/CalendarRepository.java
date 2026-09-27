package com.aido.server.calendar;

import com.aido.server.common.TimeLabels;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CalendarRepository {

  private final JdbcClient jdbc;
  private final TimeLabels time;

  public CalendarRepository(JdbcClient jdbc, TimeLabels time) {
    this.jdbc = jdbc;
    this.time = time;
  }

  /**
   * @param done      已经结束
   * @param now       正在进行
   * @param agentNote 小雀为这场日程准备的提示
   */
  public record EventDto(
      long id, String title, String place, OffsetDateTime startsAt, OffsetDateTime endsAt, String agentNote,
      boolean done, boolean now) {}

  /** 某一天（业务时区）的日程，按开始时间排序。 */
  public List<EventDto> forDay(String me, LocalDate day) {
    var from = day.atStartOfDay(time.zone()).toOffsetDateTime();
    return jdbc.sql("""
        SELECT id, title, place, starts_at, ends_at, agent_note,
               coalesce(ends_at, starts_at) < :now AS done,
               starts_at <= :now AND coalesce(ends_at, starts_at) >= :now AS now
        FROM calendar_event
        WHERE owner_id = :me AND starts_at >= :from AND starts_at < :to
        ORDER BY starts_at
        """)
        .param("me", me)
        .param("now", time.now())
        .param("from", from)
        .param("to", from.plusDays(1))
        .query(EventDto.class)
        .list();
  }
}
