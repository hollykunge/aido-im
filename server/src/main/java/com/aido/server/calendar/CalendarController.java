package com.aido.server.calendar;

import com.aido.server.calendar.CalendarRepository.EventDto;
import com.aido.server.common.CurrentUser;
import com.aido.server.common.TimeLabels;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CalendarController {

  private final CalendarRepository calendar;
  private final TimeLabels time;

  public CalendarController(CalendarRepository calendar, TimeLabels time) {
    this.calendar = calendar;
    this.time = time;
  }

  /** 某一天的日程，默认今天。 */
  @GetMapping("/api/schedule")
  public List<EventDto> schedule(
      @CurrentUser String me,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return calendar.forDay(me, date == null ? time.today() : date);
  }
}
