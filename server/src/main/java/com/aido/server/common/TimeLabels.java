package com.aido.server.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** 把日期换成界面上的相对说法：今天 / 明天 / 昨天 / 本周的周几 / M月d日。 */
@Component
public class TimeLabels {

  private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

  private final Clock clock;

  public TimeLabels(Clock clock) {
    this.clock = clock;
  }

  public ZoneId zone() {
    return clock.getZone();
  }

  public LocalDate today() {
    return LocalDate.now(clock);
  }

  public OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  public String dayLabel(OffsetDateTime t) {
    return t == null ? null : dayLabel(t.atZoneSameInstant(clock.getZone()).toLocalDate());
  }

  public String dayLabel(LocalDate d) {
    long diff = ChronoUnit.DAYS.between(today(), d);
    if (diff == 0) return "今天";
    if (diff == 1) return "明天";
    if (diff == -1) return "昨天";
    if (Math.abs(diff) < 7 && d.getDayOfWeek().compareTo(today().getDayOfWeek()) * diff > 0) {
      return WEEKDAYS[d.getDayOfWeek().getValue() - 1];
    }
    return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
  }
}
