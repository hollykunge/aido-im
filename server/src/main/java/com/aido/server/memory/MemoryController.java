package com.aido.server.memory;

import com.aido.server.common.CurrentUser;
import com.aido.server.memory.MemoryService.Item;
import com.aido.server.memory.MemoryService.Memory;
import com.aido.server.memory.MemoryService.Source;
import com.aido.server.memory.MemoryService.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 记忆页：小雀记住的关于你的画像、具体记忆和学习来源。仅本人可见。 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

  private final MemoryService memory;

  public MemoryController(MemoryService memory) {
    this.memory = memory;
  }

  @GetMapping
  public Memory get(@CurrentUser String me) {
    return memory.snapshot(me);
  }

  public record NewTag(@NotBlank @Size(max = 20) String group, @NotBlank @Size(max = 40) String tag) {}

  @PostMapping("/profile-tags")
  @ResponseStatus(HttpStatus.CREATED)
  public Tag addTag(@CurrentUser String me, @RequestBody @Valid NewTag body) {
    return memory.addTag(me, body.group().strip(), body.tag().strip());
  }

  @DeleteMapping("/profile-tags/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeTag(@CurrentUser String me, @PathVariable long id) {
    memory.removeTag(me, id);
  }

  public record ItemBody(@NotBlank @Size(max = 500) String text) {}

  @PostMapping("/items")
  @ResponseStatus(HttpStatus.CREATED)
  public Item addItem(@CurrentUser String me, @RequestBody @Valid ItemBody body) {
    return memory.addItem(me, body.text().strip());
  }

  @PatchMapping("/items/{id}")
  public Item editItem(@CurrentUser String me, @PathVariable long id, @RequestBody @Valid ItemBody body) {
    return memory.editItem(me, id, body.text().strip());
  }

  @DeleteMapping("/items/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeItem(@CurrentUser String me, @PathVariable long id) {
    memory.removeItem(me, id);
  }

  public record Toggle(@NotNull Boolean enabled) {}

  @PatchMapping("/sources/{key}")
  public Source toggleSource(@CurrentUser String me, @PathVariable String key, @RequestBody @Valid Toggle body) {
    return memory.toggleSource(me, key, body.enabled());
  }
}
