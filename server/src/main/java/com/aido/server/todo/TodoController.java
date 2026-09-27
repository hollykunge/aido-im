package com.aido.server.todo;

import com.aido.server.common.CurrentUser;
import com.aido.server.todo.TodoDtos.BatchStatus;
import com.aido.server.todo.TodoDtos.CreateTodo;
import com.aido.server.todo.TodoDtos.FromMessage;
import com.aido.server.todo.TodoDtos.TodoDto;
import com.aido.server.todo.TodoDtos.UpdateTodo;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

  private static final Set<String> STATUSES = Set.of("suggested", "open", "done", "dismissed");

  private final TodoService todos;

  public TodoController(TodoService todos) {
    this.todos = todos;
  }

  /** status 可多选，默认返回除「已忽略」以外的全部。 */
  @GetMapping
  public List<TodoDto> list(
      @CurrentUser String me, @RequestParam(defaultValue = "suggested,open,done") List<String> status) {
    if (!STATUSES.containsAll(status)) throw new IllegalArgumentException("status 只能是 " + STATUSES);
    return todos.list(me, status);
  }

  @GetMapping("/{id}")
  public TodoDto get(@CurrentUser String me, @PathVariable long id) {
    return todos.get(me, id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TodoDto create(@CurrentUser String me, @RequestBody @Valid CreateTodo body) {
    return todos.create(me, body);
  }

  /** 聊天里「转为 TODO」。 */
  @PostMapping("/from-message")
  @ResponseStatus(HttpStatus.CREATED)
  public TodoDto fromMessage(@CurrentUser String me, @RequestBody FromMessage body) {
    return todos.fromMessage(me, body.messageId());
  }

  /** 勾选完成 / 取消完成、改标题、移入移出今天等。 */
  @PatchMapping("/{id}")
  public TodoDto update(@CurrentUser String me, @PathVariable long id, @RequestBody @Valid UpdateTodo body) {
    return todos.update(me, id, body);
  }

  /** 确认或忽略小A的建议。 */
  @PostMapping("/status")
  public List<TodoDto> setStatus(@CurrentUser String me, @RequestBody @Valid BatchStatus body) {
    return todos.setStatus(me, body.ids(), body.status());
  }

  /** 让小A重新整理消息里的待办：找到新事项返回 200，已是最新返回 204。 */
  @PostMapping("/rescan")
  public ResponseEntity<TodoDto> rescan(@CurrentUser String me) {
    return todos.rescan(me).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
  }
}
