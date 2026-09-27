-- 消息附件的存储位置和类型。文件内容不进数据库，由 FileStorage 存（本地磁盘，或以后换成对象存储）。
-- storage_key 为空表示只有文件名、没有可下载的内容（如演示数据里的文件消息）。
ALTER TABLE message
  ADD COLUMN attachment_key   text,
  ADD COLUMN attachment_type  text;
