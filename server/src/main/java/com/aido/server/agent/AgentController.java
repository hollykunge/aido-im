package com.aido.server.agent;

import com.aido.server.agent.AgentFeedRepository.Capability;
import com.aido.server.common.CurrentUser;
import com.aido.server.common.NotFoundException;
import com.aido.server.realtime.RealtimeEvents;
import com.aido.server.todo.TodoDtos.TodoDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** 小雀（个人 Agent）面板。 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

  private final AgentProperties props;
  private final AgentService agent;
  private final AgentFeedRepository feed;
  private final RealtimeEvents events;

  public AgentController(AgentProperties props, AgentService agent, AgentFeedRepository feed, RealtimeEvents events) {
    this.props = props;
    this.agent = agent;
    this.feed = feed;
    this.events = events;
  }

  /** @param available false 时前端隐藏 AI 元素（小雀精选、待确认建议、建议回复等） */
  public record Profile(String name, String tagline, boolean available, List<String> quickPrompts) {}

  @GetMapping
  public Profile profile(@CurrentUser String me) {
    return new Profile(props.name(), props.tagline(), props.enabled(), feed.quickPrompts(me));
  }

  /** 对话流：最近 limit 条，按时间正序。 */
  @GetMapping("/feed")
  public List<ObjectNode> feed(@CurrentUser String me, @RequestParam(defaultValue = "100") int limit) {
    agent.requireAvailable();
    return feed.recent(me, Math.clamp(limit, 1, 500));
  }

  public record Ask(@NotBlank @Size(max = 2000) String text, AgentBrain.Context context) {}

  /** 和小雀说一句话，返回思考步骤和新增的卡片。 */
  @PostMapping("/messages")
  public AgentService.Exchange send(@CurrentUser String me, @RequestBody @Valid Ask body) {
    return agent.send(me, body.text(), body.context());
  }

  @PostMapping("/feed/{id}/accept")
  public List<TodoDto> acceptProposal(@CurrentUser String me, @PathVariable long id) {
    agent.requireAvailable();
    return agent.resolveProposal(me, id, true);
  }

  @PostMapping("/feed/{id}/dismiss")
  public List<TodoDto> dismissProposal(@CurrentUser String me, @PathVariable long id) {
    agent.requireAvailable();
    return agent.resolveProposal(me, id, false);
  }

  @PostMapping("/feed/{id}/draft/insert")
  public ObjectNode insertDraft(@CurrentUser String me, @PathVariable long id) {
    return agent.insertDraft(me, id);
  }

  @PostMapping("/feed/{id}/draft/dismiss")
  public ObjectNode dismissDraft(@CurrentUser String me, @PathVariable long id) {
    return agent.dismissDraft(me, id);
  }

  public record SendDraft(@Size(max = 4000) String text) {}

  @PostMapping("/feed/{id}/draft/send")
  public AgentService.DraftSent sendDraft(
      @CurrentUser String me, @PathVariable long id, @RequestBody(required = false) @Valid SendDraft body) {
    return agent.sendDraft(me, id, body == null ? null : body.text());
  }

  // —— 自动化能力开关（设置面板）——

  @GetMapping("/capabilities")
  public List<Capability> capabilities(@CurrentUser String me) {
    return feed.capabilities(me);
  }

  public record Toggle(@NotNull Boolean enabled) {}

  @PatchMapping("/capabilities/{key}")
  public Capability toggle(@CurrentUser String me, @PathVariable String key, @RequestBody @Valid Toggle body) {
    var saved = feed.setCapability(me, key, body.enabled()).orElseThrow(() -> new NotFoundException("能力", key));
    events.capabilitiesChanged(me);
    return saved;
  }
}
