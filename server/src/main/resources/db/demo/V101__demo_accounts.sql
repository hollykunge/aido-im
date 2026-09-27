-- 演示账号：账号名为姓名拼音，所有人的初始密码都是 aido1234（仅限演示环境）。
-- 用 pgcrypto 现场生成 bcrypt 哈希，数据库里不存明文。
CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE app_user u SET login = a.login, password_hash = '{bcrypt}' || crypt('aido1234', gen_salt('bf', 10))
FROM (VALUES
  ('me',  'linzhou'),
  ('zy',  'zhouyuan'),
  ('csq', 'chensiqi'),
  ('wl',  'wanglei'),
  ('zyf', 'zhaoyifan'),
  ('ln',  'linan'),
  ('sy',  'sunyue'),
  ('hj',  'hejing')
) AS a (id, login)
WHERE u.id = a.id;
