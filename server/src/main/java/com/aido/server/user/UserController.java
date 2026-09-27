package com.aido.server.user;

import com.aido.server.common.CurrentUser;
import com.aido.server.common.NotFoundException;
import com.aido.server.realtime.RealtimeEvents;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class UserController {

  private final UserRepository users;
  private final PreferenceRepository preferences;
  private final RealtimeEvents events;
  private final Clock clock;

  public UserController(UserRepository users, PreferenceRepository preferences, RealtimeEvents events, Clock clock) {
    this.users = users;
    this.preferences = preferences;
    this.events = events;
    this.clock = clock;
  }

  /**
   * 当前用户：通讯录里的公开资料，加上只给本人看的登录账号和本次登录时间。
   *
   * @param loggedInAt 这次登录会话的创建时间
   */
  public record Me(
      String id, String name, String role, String dept, String color, String login, OffsetDateTime loggedInAt) {}

  /**
   * @param name  显示名，1–20 个字，不能有换行等控制字符
   * @param color 头像颜色，#RRGGBB
   */
  public record ProfilePatch(
      @Size(min = 1, max = 20) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "不能包含换行等特殊字符") String name,
      @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "必须是 #RRGGBB 格式的颜色") String color) {}

  /** 改自己的显示名和头像颜色。所有人的通讯录、聊天里都会显示，所以推送给全部在线用户。 */
  @PatchMapping("/me")
  public Me updateProfile(@CurrentUser String me, @RequestBody @Valid ProfilePatch patch, HttpServletRequest request) {
    var name = patch.name() == null ? null : patch.name().strip();
    if (name != null && name.isEmpty()) throw new IllegalArgumentException("姓名不能为空");
    users.updateProfile(me, name, patch.color() == null ? null : patch.color().toLowerCase(Locale.ROOT));
    events.userUpdated(me);
    return me(me, request);
  }

  @GetMapping("/me")
  public Me me(@CurrentUser String me, HttpServletRequest request) {
    var u = users.findById(me).orElseThrow(() -> new NotFoundException("用户", me));
    var session = request.getSession(false);
    var loggedInAt = session == null ? null
        : OffsetDateTime.ofInstant(Instant.ofEpochMilli(session.getCreationTime()), clock.getZone());
    return new Me(u.id(), u.name(), u.role(), u.dept(), u.color(), users.findLogin(me).orElse(null), loggedInAt);
  }

  /** 通讯录：前端按部门分组展示。 */
  @GetMapping("/users")
  public List<UserDto> list() {
    return users.findAll();
  }

  @GetMapping("/users/{id}")
  public UserDto get(@PathVariable String id) {
    return users.findById(id).orElseThrow(() -> new NotFoundException("用户", id));
  }

  @GetMapping("/me/preferences")
  public PreferenceRepository.Preferences preferences(@CurrentUser String me) {
    return preferences.get(me);
  }

  public record PreferencePatch(
      @Pattern(regexp = "light|dark|system") String theme, JsonNode agentLook, Boolean memoryLearning) {}

  /** 设置面板：主题、小A形象；记忆页：是否从日常工作中学习。 */
  @PatchMapping("/me/preferences")
  public PreferenceRepository.Preferences updatePreferences(
      @CurrentUser String me, @RequestBody @Valid PreferencePatch patch) {
    var saved = preferences.update(me, patch.theme(), patch.agentLook(), patch.memoryLearning());
    events.preferencesChanged(me);
    return saved;
  }
}
