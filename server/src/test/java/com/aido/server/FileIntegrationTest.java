package com.aido.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/** 聊天附件：上传、下载权限、防 XSS 的下载方式、文件名清洗、大小上限；以及 @ 提及用的成员列表。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.flyway.clean-disabled=false",
    "aido.files.dir=${java.io.tmpdir}/aido-test-files",
    // 上限调小，方便测超限
    "spring.servlet.multipart.max-file-size=64KB",
    "spring.servlet.multipart.max-request-size=65KB",
})
class FileIntegrationTest {

  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

  @LocalServerPort
  int port;

  @Autowired
  Flyway flyway;

  TestSessions sessions;

  @BeforeEach
  void reset() {
    flyway.clean();
    flyway.migrate();
    sessions = new TestSessions(port);
  }

  private ResponseEntity<JsonNode> upload(TestSessions.Browser b, String conv, String name, String type, byte[] bytes) {
    var partHeaders = new HttpHeaders();
    partHeaders.setContentType(MediaType.parseMediaType(type));
    var resource = new ByteArrayResource(bytes) {
      @Override
      public String getFilename() {
        return name;
      }
    };
    var body = new LinkedMultiValueMap<String, Object>();
    body.add("file", new HttpEntity<>(resource, partHeaders));
    return b.api.post().uri("/conversations/{id}/files", conv).contentType(MediaType.MULTIPART_FORM_DATA)
        .body(body).retrieve().toEntity(JsonNode.class);
  }

  private ResponseEntity<byte[]> download(TestSessions.Browser b, long messageId, boolean forceDownload) {
    return b.api.get().uri("/files/{id}" + (forceDownload ? "?download" : ""), messageId)
        .retrieve().toEntity(byte[].class);
  }

  @Test
  void uploadSendsFileMessageThatMembersCanDownload() {
    var me = sessions.as("me");
    var bytes = "周五 demo 范围".getBytes(StandardCharsets.UTF_8);
    var res = upload(me, "c4", "范围.txt", "text/plain", bytes);
    assertThat(res.getStatusCode().value()).isEqualTo(201);
    var file = res.getBody().path("file");
    assertThat(file.path("name").asString()).isEqualTo("范围.txt");
    assertThat(file.path("size").asLong()).isEqualTo(bytes.length);
    assertThat(file.path("available").asBoolean()).isTrue();
    long id = res.getBody().path("id").asLong();

    // 会话另一方能下载，内容一致；非图片强制下载
    var got = download(sessions.as("zy"), id, false);
    assertThat(got.getStatusCode().value()).isEqualTo(200);
    assertThat(got.getBody()).isEqualTo(bytes);
    assertThat(got.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(got.getHeaders().getContentDisposition().isAttachment()).isTrue();
    assertThat(got.getHeaders().getContentDisposition().getFilename()).isEqualTo("范围.txt");

    // 不在会话里的人下载不到（当作不存在）
    assertThat(download(sessions.as("csq"), id, false).getStatusCode().value()).isEqualTo(404);
    // 会话列表预览
    var conv = me.api.get().uri("/conversations/c4").retrieve().body(JsonNode.class);
    assertThat(conv.path("lastMessage").asString()).isEqualTo("[文件] 范围.txt");
  }

  @Test
  void safeImagesShowInlineEverythingElseDownloads() {
    var me = sessions.as("me");
    var png = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    long image = upload(me, "c1", "截图.png", "image/png", png).getBody().path("id").asLong();
    var inline = download(me, image, false);
    assertThat(inline.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(inline.getHeaders().getContentDisposition().isInline()).isTrue();
    assertThat(download(me, image, true).getHeaders().getContentDisposition().isAttachment()).isTrue();
    assertThat(me.api.get().uri("/conversations/c1").retrieve().body(JsonNode.class).path("lastMessage").asString())
        .isEqualTo("[图片]");

    // 上传 HTML / SVG 也不会在我们的域名下被当页面执行
    for (var type : List.of("text/html", "image/svg+xml")) {
      long id = upload(me, "c1", "x", type, "<script>alert(1)</script>".getBytes()).getBody().path("id").asLong();
      var res = download(me, id, false);
      assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
      assertThat(res.getHeaders().getContentDisposition().isAttachment()).isTrue();
      assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
      assertThat(res.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
    }
  }

  @Test
  void filenamesAreSanitizedAndLimitsEnforced() {
    var me = sessions.as("me");
    var res = upload(me, "c1", "../../etc/pass\u0000wd.txt", "text/plain", "x".getBytes());
    assertThat(res.getBody().path("file").path("name").asString()).isEqualTo("passwd.txt");

    assertThat(upload(me, "c1", "big.bin", "application/octet-stream", new byte[100 * 1024]).getStatusCode().value())
        .isEqualTo(413);
    assertThat(upload(me, "c1", "empty.txt", "text/plain", new byte[0]).getStatusCode().value()).isEqualTo(400);
    // 不在会话里不能往里发
    assertThat(upload(sessions.as("csq"), "c4", "a.txt", "text/plain", "x".getBytes()).getStatusCode().value())
        .isEqualTo(404);
    // 演示数据里的文件消息只有文件名、没有内容
    assertThat(download(me, 104, false).getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void membersForMentions() {
    var members = sessions.as("me").api.get().uri("/conversations/c4/members")
        .retrieve().body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    assertThat(members).extracting(m -> m.get("id")).containsExactlyInAnyOrder("me", "zy");
    assertThat(sessions.as("csq").api.get().uri("/conversations/c4/members").retrieve().toBodilessEntity()
        .getStatusCode().value()).isEqualTo(404);
  }
}
