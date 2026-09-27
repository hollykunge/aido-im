package com.aido.server.files;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * 附件内容的存储。数据库只存元数据（文件名、大小、类型、key），内容放这里。
 * 现在是本地磁盘实现；多实例部署时换成对象存储（S3 / MinIO / OSS）的实现即可，其他代码不用改。
 */
public interface FileStorage {

  /** 保存内容，返回之后读取用的 key。 */
  String save(InputStream content) throws IOException;

  /** 按 key 读取；不存在时为空。 */
  Optional<InputStream> open(String key) throws IOException;

  void delete(String key) throws IOException;
}
