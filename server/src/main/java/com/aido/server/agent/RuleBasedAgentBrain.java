package com.aido.server.agent;

import com.aido.server.calendar.CalendarRepository;
import com.aido.server.chat.ChatRepository;
import com.aido.server.chat.ChatDtos.ConversationDto;
import com.aido.server.common.TimeLabels;
import com.aido.server.todo.TodoDtos.TodoDto;
import com.aido.server.todo.TodoRepository;
import com.aido.server.todo.TodoService;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 按关键词匹配意图的演示实现，与前端原先 stores/agent.js 里的 replyFor 行为一致，数据改为读库。 */
@Component
public class RuleBasedAgentBrain implements AgentBrain {

  private static final Pattern DRAFT = Pattern.compile("回复|起草|草稿");
  // 不能只匹配「会」，否则「总结当前会话」也会被当成问日程
  private static final Pattern SCHEDULE = Pattern.compile("会议|开会|哪些会|几个会|日程|日历");
  private static final Pattern RESCAN = Pattern.compile("整理|扫描|重新");
  private static final Pattern TODO = Pattern.compile("任务|待办|todo|排", Pattern.CASE_INSENSITIVE);
  private static final Pattern MEMORY = Pattern.compile("记得|记忆|习惯");
  private static final Pattern SUMMARY = Pattern.compile("总结|摘要|讲了什么");
  private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

  private final ChatRepository chat;
  private final TodoRepository todos;
  private final TodoService todoService;
  private final CalendarRepository calendar;
  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final TimeLabels time;

  public RuleBasedAgentBrain(
      ChatRepository chat, TodoRepository todos, TodoService todoService, CalendarRepository calendar,
      JdbcClient jdbc, JsonMapper json, TimeLabels time) {
    this.chat = chat;
    this.todos = todos;
    this.todoService = todoService;
    this.calendar = calendar;
    this.jdbc = jdbc;
    this.json = json;
    this.time = time;
  }

  @Override
  public Reply reply(String me, String text, Context ctx) {
    var conv = targetConversation(me, ctx);
    if (DRAFT.matcher(text).find() && conv.isPresent()) return draft(me, conv.get());
    if (SUMMARY.matcher(text).find() && conv.isPresent()) return summary(me, conv.get());
    if (SCHEDULE.matcher(text).find()) return schedule(me);
    if (RESCAN.matcher(text).find() && (text.contains("待办") || text.toLowerCase().contains("todo"))) return rescan(me);
    if (TODO.matcher(text).find()) return plan(me);
    if (MEMORY.matcher(text).find()) return memory(me);
    // 在聊天页里问别的，默认讲当前会话
    if (ctx != null && "chat".equals(ctx.view()) && conv.isPresent()) return summary(me, conv.get());
    return new Reply(List.of("理解你的问题", "查找相关消息、TODO 和文档"), List.of(agent(
        List.of("收到。这是演示环境，我能处理的示例指令有：总结当前会话、今天有哪些会、帮我起草回复、我的 TODO 怎么排、整理消息里的待办。"),
        List.of(), null)));
  }

  /** 上下文里的会话；没有时取消息坞第一个（置顶优先）。 */
  private Optional<ConversationDto> targetConversation(String me, Context ctx) {
    if (ctx != null && ctx.conversationId() != null) {
      var c = chat.findConversation(me, ctx.conversationId());
      if (c.isPresent()) return c;
    }
    return chat.listConversations(me, "all").stream().findFirst();
  }

  private Reply draft(String me, ConversationDto conv) {
    var name = conv.title();
    var drafts = chat.findSuggestions(me, conv.id(), "draft");
    var body = drafts.isEmpty() ? "收到，我看一下，晚点回复你。" : drafts.getFirst();
    var card = json.createObjectNode()
        .put("conversationId", conv.id())
        .put("conversationName", name)
        .put("state", "pending")
        .put("text", body);
    return new Reply(
        List.of("读取「" + name + "」最近消息", "对照你的 TODO 与日程", "按你的语气起草"),
        List.of(agent(List.of("我按你平时的语气起草了一条给「" + name + "」的回复："), List.of(), null),
            new Card("draft", card)));
  }

  private Reply schedule(String me) {
    var left = calendar.forDay(me, time.today()).stream().filter(e -> !e.done()).toList();
    var bullets = left.stream()
        .map(e -> bullet(e.startsAt().atZoneSameInstant(time.zone()).format(HM) + " " + e.title(),
            e.agentNote() == null ? "" : " —— " + e.agentNote(), null))
        .toList();
    var intro = left.isEmpty() ? "今天没有其他安排了。" : "今天还剩 " + left.size() + " 个安排：";
    return new Reply(List.of("查看今天的日历", "关联相关消息和文档"), List.of(agent(List.of(intro), bullets, null)));
  }

  private Reply rescan(String me) {
    int convCount = chat.listConversations(me, "all").size();
    var steps = List.of("扫描 " + convCount + " 个会话的新消息", "识别需要你行动的内容", "跳过已在 TODO 里的");
    var found = todoService.rescan(me);
    if (found.isEmpty()) {
      return new Reply(steps, List.of(agent(List.of("消息里没有新的待办了，TODO 已经是最新的。"), List.of(), null)));
    }
    var t = found.get();
    var from = t.source() == null ? "消息" : "「" + t.source().conversationName() + "」";
    var card = json.createObjectNode().put("text", "从" + from + "里又找到 1 件可能要你做的事，已放进 TODO 的「待确认」：");
    card.putArray("todoIds").add(t.id());
    return new Reply(steps, List.of(new Card("proposal", card)));
  }

  private Reply plan(String me) {
    var open = todos.list(me, List.of("open"));
    var today = open.stream().filter(TodoDto::today).toList();
    var bullets = new ArrayList<ObjectNode>();
    for (int i = 0; i < Math.min(4, today.size()); i++) {
      var t = today.get(i);
      bullets.add(bullet(i == 0 ? "现在" : t.due(), " " + t.title(), "/todo?focus=" + t.id()));
    }
    int notToday = open.size() - today.size();
    return new Reply(
        List.of("读取你的 TODO", "结合今天的会议空档排序"),
        List.of(agent(
            List.of("你有 " + open.size() + " 项 TODO，其中 " + today.size() + " 项是今天的。按优先级和截止时间，建议这样排："),
            bullets,
            notToday > 0 ? "另外 " + notToday + " 项不是今天的，今天不用赶。" : null)));
  }

  private Reply memory(String me) {
    var items = jdbc.sql("SELECT body FROM memory_item WHERE owner_id = ? ORDER BY created_at DESC LIMIT 3")
        .param(me)
        .query(String.class)
        .list();
    return new Reply(List.of("读取你的记忆"), List.of(agent(
        List.of("我记得这些，和你的日常安排关系最大："),
        items.stream().map(s -> bullet(null, s, null)).toList(),
        "有记错的地方，在「记忆」页直接改就行。")));
  }

  private Reply summary(String me, ConversationDto conv) {
    var name = conv.title();
    var sum = chat.findSummary(me, conv.id());
    List<String> points;
    int count;
    if (sum.isPresent()) {
      points = sum.get().points();
      count = sum.get().count();
    } else {
      // 还没有生成过摘要：取最近 3 条消息
      var recent = chat.listMessages(me, conv.id(), null, 3);
      points = recent.stream().map(m -> m.text() != null ? m.text() : "[文件] " + m.file().name()).toList();
      count = recent.size();
    }
    boolean needsReply = todos.list(me, List.of("open")).stream()
        .anyMatch(t -> "reply".equals(t.kind()) && t.source() != null && conv.id().equals(t.source().conversationId()));
    return new Reply(
        List.of("读取「" + name + "」的 " + count + " 条消息", "去掉日常寒暄和重复信息", "提炼要点"),
        List.of(agent(
            List.of("「" + name + "」的要点："),
            points.stream().map(p -> bullet(null, p, null)).toList(),
            needsReply ? "有一条需要你回复，要我起草吗？" : null)));
  }

  // —— 卡片构造 ——

  private Card agent(List<String> paragraphs, List<ObjectNode> bullets, String tail) {
    var card = json.createObjectNode();
    ArrayNode ps = card.putArray("paragraphs");
    paragraphs.forEach(ps::add);
    if (!bullets.isEmpty()) card.putArray("bullets").addAll(bullets);
    if (tail != null) card.put("tail", tail);
    return new Card("agent", card);
  }

  private ObjectNode bullet(String strong, String text, String link) {
    var b = json.createObjectNode();
    if (strong != null) b.put("strong", strong);
    b.put("text", text);
    if (link != null) b.put("link", link);
    return b;
  }
}
