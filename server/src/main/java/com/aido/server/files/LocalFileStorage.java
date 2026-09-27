package com.aido.server.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 存在本地目录。key 是随机 UUID（不用原文件名，避免路径穿越和重名），按前两位分子目录，避免单个目录文件过多。
 * 先写临时文件再原子移动，写到一半失败不会留下残缺文件。
 */
@Component
public class LocalFileStorage implements FileStorage {

  private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final Path root;

  public LocalFileStorage(@Value("${aido.files.dir:./data/files}") Path root) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    Files.createDirectories(this.root);
  }

  @Override
  public String save(InputStream content) throws IOException {
    var key = UUID.randomUUID().toString();
    var target = path(key);
    Files.createDirectories(target.getParent());
    var tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
    try {
      Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp);
    }
    return key;
  }

  @Override
  public Optional<InputStream> open(String key) throws IOException {
    try {
      return Optional.of(Files.newInputStream(path(key)));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }

  @Override
  public void delete(String key) throws IOException {
    Files.deleteIfExists(path(key));
  }

  private Path path(String key) {
    // key 只可能是我们自己生成的 UUID；校验一下，杜绝拼出根目录以外的路径
    if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("无效的文件 key");
    return root.resolve(key.substring(0, 2)).resolve(key);
  }
}
