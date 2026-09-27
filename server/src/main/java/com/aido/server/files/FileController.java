package com.aido.server.files;

import com.aido.server.chat.ChatDtos.MessageDto;
import com.aido.server.chat.ChatRepository;
import com.aido.server.chat.ChatService;
import com.aido.server.common.CurrentUser;
import com.aido.server.common.NotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天附件：上传即发送一条文件消息；下载只允许会话成员。
 *
 * <p>安全处理：
 * <ul>
 *   <li>文件名只保留最后一段、去掉控制字符，存储用随机 key，原文件名不参与路径。</li>
 *   <li>只有常见位图（png/jpeg/gif/webp）可以在页面里直接显示；其余一律按
 *       application/octet-stream 强制下载——否则上传一个 HTML 或 SVG 就能在我们的域名下跑脚本。</li>
 *   <li>响应带 nosniff（Spring Security 默认）和 CSP sandbox，浏览器不会把文件当页面执行。</li>
 * </ul>
 */
@RestController
public class FileController {

  private static final Logger log = LoggerFactory.getLogger(FileController.class);

  static final Set<String> INLINE_IMAGES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
  private static final Pattern MIME = Pattern.compile("[a-z0-9.+-]+/[a-z0-9.+-]+");
  private static final int NAME_MAX = 200;

  private final FileStorage storage;
  private final ChatService chat;
  private final ChatRepository repo;

  public FileController(FileStorage storage, ChatService chat, ChatRepository repo) {
    this.storage = storage;
    this.chat = chat;
    this.repo = repo;
  }

  /** 上传一个文件并作为消息发到会话里。大小上限见 spring.servlet.multipart.max-file-size。 */
  @PostMapping("/api/conversations/{id}/files")
  @ResponseStatus(HttpStatus.CREATED)
  public MessageDto upload(@CurrentUser String me, @PathVariable String id, @RequestParam("file") MultipartFile file)
      throws IOException {
    // 先确认是成员，不是的话连文件都不存
    chat.requireMember(me, id);
    if (file.isEmpty()) throw new IllegalArgumentException("不能发送空文件");
    var name = cleanName(file.getOriginalFilename());
    var type = cleanType(file.getContentType());

    String key;
    try (var in = file.getInputStream()) {
      key = storage.save(in);
    }
    try {
      return chat.sendFile(me, id, name, file.getSize(), type, key);
    } catch (RuntimeException e) {
      // 消息没写进去，别留下没人引用的文件
      try {
        storage.delete(key);
      } catch (IOException cleanup) {
        log.warn("清理上传失败的文件出错 {}", key, cleanup);
      }
      throw e;
    }
  }

  /** 下载附件。安全的图片默认在页面里显示（缩略图用），加 ?download 强制下载。 */
  @GetMapping("/api/files/{messageId}")
  public ResponseEntity<InputStreamResource> download(
      @CurrentUser String me, @PathVariable long messageId, @RequestParam(required = false) String download)
      throws IOException {
    var file = repo.findFile(messageId).orElseThrow(() -> new NotFoundException("文件", messageId));
    // 非成员一律当作不存在
    chat.requireMember(me, file.conversationId());
    if (file.key() == null) throw new NotFoundException("文件内容", file.name());
    var content = storage.open(file.key()).orElseThrow(() -> new NotFoundException("文件内容", file.name()));

    boolean inline = download == null && INLINE_IMAGES.contains(file.type());
    var disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
        .filename(file.name(), StandardCharsets.UTF_8)
        .build();
    return ResponseEntity.ok()
        .contentType(inline ? MediaType.parseMediaType(file.type()) : MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(file.size())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        // 附件内容不会变，私有缓存一天
        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
        .body(new InputStreamResource(content));
  }

  static String cleanName(String original) {
    var name = original == null ? "" : original;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name = name.replaceAll("\\p{Cntrl}", "").strip();
    if (name.isEmpty()) name = "未命名文件";
    return name.length() > NAME_MAX ? name.substring(0, NAME_MAX) : name;
  }

  static String cleanType(String declared) {
    var type = declared == null ? "" : declared.toLowerCase().split(";")[0].strip();
    return MIME.matcher(type).matches() ? type : MediaType.APPLICATION_OCTET_STREAM_VALUE;
  }
}
