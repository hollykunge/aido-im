package com.aido.server.todo;

import com.aido.server.chat.ChatRepository;
import com.aido.server.chat.ChatService;
import com.aido.server.common.NotFoundException;
import com.aido.server.common.TimeLabels;
import com.aido.server.realtime.RealtimeEvents;
import com.aido.server.todo.TodoDtos.CreateTodo;
import com.aido.server.todo.TodoDtos.TodoDto;
import com.aido.server.todo.TodoDtos.UpdateTodo;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TodoService {

  /** 从消息生成 TODO 时标题最多保留的字数。 */
  static final int TITLE_FROM_MESSAGE_MAX = 28;

  private final TodoRepository repo;
  private final ChatRepository chat;
  private final ChatService chatService;
  private final TimeLabels time;
  private final RealtimeEvents events;

  public TodoService(
      TodoRepository repo, ChatRepository chat, ChatService chatService, TimeLabels time, RealtimeEvents events) {
    this.repo = repo;
    this.chat = chat;
    this.chatService = chatService;
    this.time = time;
    this.events = events;
  }

  /** 改完后通知这个人的其他标签页。 */
  private TodoDto changed(String me, long id) {
    events.todosChanged(me, List.of(id));
    return get(me, id);
  }

  public List<TodoDto> list(String me, Collection<String> statuses) {
    return repo.list(me, statuses);
  }

  public TodoDto get(String me, long id) {
    return repo.find(me, id).orElseThrow(() -> new NotFoundException("TODO", id));
  }

  /** 手动新建，默认放进今天。 */
  @Transactional
  public TodoDto create(String me, CreateTodo body) {
    boolean today = body.today() == null || body.today();
    long id = repo.insert(me, body.title().strip(), "open", "task", body.priority(),
        today ? time.today() : null, null, body.dueAt(), null, null, "user");
    return changed(me, id);
  }

  /** 把一条消息转为 TODO，保留原消息来源；同一条消息已有未忽略的 TODO 时报冲突。 */
  @Transactional
  public TodoDto fromMessage(String me, long messageId) {
    var msg = chat.findMessage(me, messageId).orElseThrow(() -> new NotFoundException("消息", messageId));
    chatService.requireMember(me, msg.conversationId());
    if (repo.findLiveBySource(me, messageId).isPresent()) throw new IllegalStateException("这条消息已经在 TODO 里了");

    var raw = msg.text() != null ? msg.text() : "处理文件：" + msg.file().name();
    var title = raw.replaceAll("@\\S+\\s*", "").strip();
    if (title.isEmpty()) title = raw.strip();
    if (title.length() > TITLE_FROM_MESSAGE_MAX) title = title.substring(0, TITLE_FROM_MESSAGE_MAX) + "…";
    long id = repo.insert(me, title, "open", "task", null, time.today(), null, null, null, messageId, "user");
    return changed(me, id);
  }

  @Transactional
  public TodoDto update(String me, long id, UpdateTodo patch) {
    if (repo.update(me, id, patch) == 0) throw new NotFoundException("TODO", id);
    return changed(me, id);
  }

  /** 批量改状态，返回改动后的各项（不属于当前用户的 id 会被忽略）。 */
  @Transactional
  public List<TodoDto> setStatus(String me, Collection<Long> ids, String status) {
    repo.updateStatus(me, ids, status);
    events.todosChanged(me, ids);
    return repo.findAll(me, ids);
  }

  /** 「重新整理」：把小雀新识别出的下一件事放进待确认；没有新事项时为空。 */
  @Transactional
  public Optional<TodoDto> rescan(String me) {
    return repo.promoteNextCandidate(me).map(id -> changed(me, id));
  }
}
