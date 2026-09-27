package com.aido.server.chat;

import com.aido.server.chat.ChatDtos.ConversationDto;
import com.aido.server.chat.ChatDtos.MessageDto;
import com.aido.server.chat.ChatDtos.OpenDm;
import com.aido.server.chat.ChatDtos.SendMessage;
import com.aido.server.chat.ChatDtos.SummaryDto;
import com.aido.server.common.CurrentUser;
import com.aido.server.user.UserDto;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ChatController {

  private final ChatService chat;
  private final ChatRepository repo;

  public ChatController(ChatService chat, ChatRepository repo) {
    this.chat = chat;
    this.repo = repo;
  }

  /** 消息坞：filter = all | unread | flag（小A精选）。 */
  @GetMapping
  public List<ConversationDto> list(@CurrentUser String me, @RequestParam(defaultValue = "all") String filter) {
    return chat.list(me, filter);
  }

  /** 左侧「群组」分栏。 */
  @GetMapping("/groups")
  public List<ConversationDto> groups(@CurrentUser String me) {
    return repo.listGroups(me);
  }

  @GetMapping("/{id}")
  public ConversationDto get(@CurrentUser String me, @PathVariable String id) {
    return chat.get(me, id);
  }

  /** 从通讯录 / 搜索打开与某人的私聊，没有就新建。 */
  @PostMapping("/dm")
  public ConversationDto openDm(@CurrentUser String me, @RequestBody @Valid OpenDm body) {
    return chat.openDm(me, body.userId());
  }

  /** 消息按时间正序返回；向上翻页时把最早一条的 id 作为 before 传入。 */
  @GetMapping("/{id}/messages")
  public List<MessageDto> messages(
      @CurrentUser String me,
      @PathVariable String id,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "50") int limit) {
    return chat.messages(me, id, before, limit);
  }

  @PostMapping("/{id}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  public MessageDto send(@CurrentUser String me, @PathVariable String id, @RequestBody @Valid SendMessage body) {
    return chat.send(me, id, body.text());
  }

  @PostMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markRead(@CurrentUser String me, @PathVariable String id) {
    chat.markRead(me, id);
  }

  /** 会话成员：输入框里 @ 提及时选人用。 */
  @GetMapping("/{id}/members")
  public List<UserDto> members(@CurrentUser String me, @PathVariable String id) {
    return chat.members(me, id);
  }

  /** 小A的未读摘要；没有摘要时返回 204。 */
  @GetMapping("/{id}/summary")
  public ResponseEntity<SummaryDto> summary(@CurrentUser String me, @PathVariable String id) {
    chat.requireMember(me, id);
    return repo.findSummary(me, id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /** 输入框上方的「建议回复」。 */
  @GetMapping("/{id}/suggested-replies")
  public List<String> suggestedReplies(@CurrentUser String me, @PathVariable String id) {
    chat.requireMember(me, id);
    return repo.findSuggestions(me, id, "quick");
  }
}
