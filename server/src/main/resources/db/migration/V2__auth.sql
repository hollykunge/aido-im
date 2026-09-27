-- 登录鉴权：账号、密码，以及 Spring Session 的会话表

-- login：登录用的账号名（不区分大小写唯一）。password_hash 带算法前缀，如 {bcrypt}$2a$10$...，
-- 以后升级哈希算法时旧密码仍能校验，用户下次登录时再换成新算法。
ALTER TABLE app_user
  ADD COLUMN login          text,
  ADD COLUMN password_hash  text,
  ADD COLUMN disabled       boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX app_user_login_idx ON app_user (lower(login));

-- 以下为 Spring Session JDBC 的标准表结构（spring-session-jdbc 自带的 schema-postgresql.sql）
CREATE TABLE spring_session (
  primary_id             char(36) NOT NULL,
  session_id             char(36) NOT NULL,
  creation_time          bigint NOT NULL,
  last_access_time       bigint NOT NULL,
  max_inactive_interval  int NOT NULL,
  expiry_time            bigint NOT NULL,
  principal_name         varchar(100),
  CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
  session_primary_id  char(36) NOT NULL,
  attribute_name      varchar(200) NOT NULL,
  attribute_bytes     bytea NOT NULL,
  CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
  CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
    REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
