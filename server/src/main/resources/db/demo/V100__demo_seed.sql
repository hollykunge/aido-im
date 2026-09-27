-- 演示数据：与前端 src/mock/index.js 一致。只在 demo profile 下执行（见 application-demo.yml）。
-- 时间都相对「今天」生成，任何一天启动都像是当天的工作现场。
SET LOCAL timezone = 'Asia/Shanghai';

CREATE FUNCTION pg_temp.at(days integer, t time) RETURNS timestamptz
  LANGUAGE sql AS $$ SELECT (current_date + days + t)::timestamptz $$;

-- —— 人 ——
INSERT INTO app_user (id, name, role, dept, color) VALUES
  ('me',  '林舟',   '前端负责人', '研发部',     '#12b5a0'),
  ('zy',  '周远',   '产品经理',   '产品部',     '#f59e0b'),
  ('csq', '陈思琪', '设计师',     '设计部',     '#ec4899'),
  ('wl',  '王磊',   '后端工程师', '研发部',     '#6366f1'),
  ('zyf', '赵一帆', '前端工程师', '研发部',     '#0ea5e9'),
  ('ln',  '李楠',   '测试工程师', '研发部',     '#84cc16'),
  ('sy',  '孙悦',   '运营',       '运营部',     '#f97316'),
  ('hj',  '何静',   'HR',         '人力资源部', '#a855f7');

INSERT INTO user_preference (user_id) VALUES ('me');

-- —— 会话 ——
INSERT INTO conversation (id, type, name, color, member_count) VALUES
  ('c1', 'group', 'AIDo 5.0 项目组', '#12b5a0', 18),
  ('c2', 'dm',    NULL,              NULL,      2),
  ('c3', 'group', '设计评审',        '#ec4899', 9),
  ('c4', 'dm',    NULL,              NULL,      2),
  ('c5', 'group', '前端技术群',      '#0ea5e9', 24),
  ('c6', 'dm',    NULL,              NULL,      2),
  ('c7', 'group', '全员通知',        '#64748b', 312),
  ('c8', 'dm',    NULL,              NULL,      2);

INSERT INTO conversation_member (conversation_id, user_id, pinned, muted, unread_count, agent_flag) VALUES
  ('c1', 'me', true,  false, 32, '有人 @你'),
  ('c2', 'me', false, false, 2,  '评审相关'),
  ('c3', 'me', false, false, 5,  NULL),
  ('c4', 'me', false, false, 2,  '待回复'),
  ('c5', 'me', false, true,  12, NULL),
  ('c6', 'me', false, false, 0,  NULL),
  ('c7', 'me', false, true,  1,  NULL),
  ('c8', 'me', false, false, 0,  NULL);

INSERT INTO conversation_member (conversation_id, user_id) VALUES
  ('c1', 'zy'), ('c1', 'csq'), ('c1', 'wl'), ('c1', 'zyf'), ('c1', 'ln'), ('c1', 'sy'),
  ('c2', 'csq'),
  ('c3', 'zy'), ('c3', 'csq'), ('c3', 'wl'), ('c3', 'zyf'),
  ('c4', 'zy'),
  ('c5', 'zyf'), ('c5', 'wl'),
  ('c6', 'wl'),
  ('c7', 'zy'), ('c7', 'csq'), ('c7', 'wl'), ('c7', 'zyf'), ('c7', 'ln'), ('c7', 'sy'), ('c7', 'hj'),
  ('c8', 'ln');

-- —— 消息（id 按「会话号 × 100 + 序号」编排，便于下面引用）——
INSERT INTO message (id, conversation_id, sender_id, body, attachment_name, attachment_size, sent_at) VALUES
  (101, 'c1', 'zy',  '今天下午两点设计评审，大家提前看下 Figma 链接', NULL, NULL, pg_temp.at(0, '09:12')),
  (102, 'c1', 'wl',  '消息同步接口 v2 已经部署到测试环境了，字段变更见文档第 3 节', NULL, NULL, pg_temp.at(0, '09:30')),
  (103, 'c1', 'zyf', '前端这边 IM 虚拟列表的性能优化 PR 提了，长会话滚动帧率从 38 提到 58，求 review', NULL, NULL, pg_temp.at(0, '09:41')),
  (104, 'c1', 'csq', NULL, 'AIDo5.0-Agent交互稿-v4.fig', 25794970, pg_temp.at(0, '10:05')),
  (105, 'c1', 'zy',  '@林舟 Agent 外壳这个方案前端这周能出个可交互 demo 吗？周五给老板看', NULL, NULL, pg_temp.at(0, '10:20')),
  (106, 'c1', 'ln',  '测试环境今天 3 点会重启一次，注意保存数据', NULL, NULL, pg_temp.at(0, '10:32')),
  (107, 'c1', 'me',  '收到，我先把基础页面搭出来，下午评审后确认范围', NULL, NULL, pg_temp.at(0, '10:40')),
  (108, 'c1', 'sy',  '运营那边希望 Agent 首发有个引导页 🙏', NULL, NULL, pg_temp.at(0, '11:02')),

  (201, 'c2', 'csq', '林舟，评审稿我更新了', NULL, NULL, pg_temp.at(0, '10:46')),
  (202, 'c2', 'csq', '你重点看下交互动效部分，Agent 面板展开收起那里我改成了弹性曲线', NULL, NULL, pg_temp.at(0, '10:48')),

  (301, 'c3', 'csq', NULL, 'AIDo5.0-Agent交互稿-v4.fig', 25794970, pg_temp.at(0, '10:05')),
  (302, 'c3', 'zy',  '评审结束后，各端把反馈整理一下发群里，我周一统一排优先级', NULL, NULL, pg_temp.at(0, '10:12')),

  (401, 'c4', 'zy',  '早', NULL, NULL, pg_temp.at(0, '09:48')),
  (402, 'c4', 'zy',  'PRD v3 里 Agent 这块你评估下工作量，明天给我个大概就行', NULL, NULL, pg_temp.at(0, '09:50')),
  (403, 'c4', 'zy',  '下午 1:1 顺便聊下 demo 的范围', NULL, NULL, pg_temp.at(0, '09:52')),

  (501, 'c5', 'zyf', '@林舟 虚拟列表 PR 提了，帮忙 review 一下，今天能合进去最好', NULL, NULL, pg_temp.at(0, '09:41')),

  (601, 'c6', 'wl',  '接口文档已更新，v2 字段看第 3 节，前端组那边你同步下？', NULL, NULL, pg_temp.at(-1, '17:20')),
  (602, 'c6', 'me',  '好，已经同步给前端组了', NULL, NULL, pg_temp.at(-1, '17:45')),

  (701, 'c7', 'hj',  '国庆值班表已出，请大家确认自己的值班日期，9 月 28 日前回复', NULL, NULL, pg_temp.at(-1, '16:00')),

  (801, 'c8', 'ln',  '消息同步这块的回归用例，你看下要补哪些范围？', NULL, NULL, pg_temp.at(-3, '15:10')),
  (802, 'c8', 'me',  '会话切换和断网重连两块，我发你清单', NULL, NULL, pg_temp.at(-3, '15:30')),
  (803, 'c8', 'ln',  '好的，回归用例我今天补上', NULL, NULL, pg_temp.at(-3, '15:32'));
SELECT setval(pg_get_serial_sequence('message', 'id'), 10000);

INSERT INTO message_mention (message_id, user_id) VALUES (105, 'me'), (501, 'me');

UPDATE conversation c SET last_message_at = m.last FROM (
  SELECT conversation_id, max(sent_at) AS last FROM message GROUP BY conversation_id
) m WHERE m.conversation_id = c.id;

-- —— 小A的摘要与回复建议 ——
INSERT INTO conversation_summary (conversation_id, owner_id, message_count, points) VALUES
  ('c1', 'me', 32, ARRAY[
    '周远 @你：Agent 外壳 demo 周五能否给到，需要你答复',
    '王磊：消息同步 v2 已上测试环境，前端可开始联调',
    '李楠：测试环境 15:00 重启，注意保存数据'
  ]);

INSERT INTO reply_suggestion (conversation_id, owner_id, kind, body, position) VALUES
  ('c1', 'me', 'quick', '可以，周五上午给到可交互 demo', 0),
  ('c1', 'me', 'quick', '我评估一下，下午评审后答复你', 1),
  ('c1', 'me', 'quick', '基础页面今天出，交互细节周四对齐', 2),
  ('c2', 'me', 'quick', '好的，评审前我过一遍', 0),
  ('c2', 'me', 'quick', '弹性曲线的参数发我一下？', 1),
  ('c4', 'me', 'quick', '好，明天上午给你粗估', 0),
  ('c4', 'me', 'quick', '需要先确认 Agent 的能力边界', 1),
  ('c1', 'me', 'draft', '@周远 可以。基础页面今天出，周四和思琪对齐交互细节，周五上午给到可交互 demo。', 0),
  ('c4', 'me', 'draft', '好的，明天上午给你粗估。Agent 模块初步看 8–10 人天，细项我整理成表发你。', 0),
  ('c5', 'me', 'draft', '看完了，整体没问题，有两处小建议我直接评论在 PR 里了，改完就可以合。', 0),
  (NULL, 'me', 'draft', '收到，我看一下，晚点回复你。', 0);

-- —— TODO ——
-- due_label 只放小A给的模糊说法（「14:00 前」「待定」）；「今天」「明天」「9月27日」由后端按日期实时换算
INSERT INTO todo (id, owner_id, title, status, kind, priority, planned_date, due_label, due_at, note, source_message_id, created_by, completed_at) VALUES
  (1,  'me', '答复周远：Agent demo 周五能否给到', 'open', 'reply', 'P0', current_date, NULL, NULL,
       '周远 @你 要一个明确答复，demo 周五要给老板看', 105, 'agent', NULL),
  (2,  'me', 'Review 赵一帆的 IM 虚拟列表 PR', 'open', 'reply', 'P1', current_date, NULL, NULL,
       '对方希望今天合入，已经等了 1 天', 501, 'agent', NULL),
  (3,  'me', '评审前看交互稿 v4 的动效部分', 'open', 'task', 'P1', current_date, '14:00 前', pg_temp.at(0, '14:00'),
       '截止时间取自今天 14:00 的设计评审', 202, 'agent', NULL),
  (4,  'me', '测试环境重启前保存联调数据', 'open', 'task', 'P2', current_date, '15:00 前', pg_temp.at(0, '15:00'),
       '你今天在测试环境联调消息同步 v2', 106, 'agent', NULL),
  (5,  'me', '评估 PRD v3 Agent 模块工作量', 'open', 'reply', 'P1', NULL, NULL, pg_temp.at(1, '18:00'),
       '截止时间取自消息里的「明天给我个大概」', 402, 'agent', NULL),
  (6,  'me', '消息同步 v2 前端联调', 'open', 'task', 'P1', NULL, NULL, pg_temp.at(2, '18:00'),
       '接口已上测试环境，字段变更见文档第 3 节', 102, 'agent', NULL),
  (7,  'me', '确认国庆值班日期（10月3日）', 'open', 'task', 'P2', NULL, NULL, pg_temp.at(3, '18:00'),
       '截止时间取自「9 月 28 日前回复」，和你的日历没有冲突', 701, 'agent', NULL),
  (8,  'me', '整理设计评审反馈，发到设计评审群', 'suggested', 'task', 'P1', current_date, NULL, NULL,
       '周远要各端评审后反馈，你是前端负责人', 302, 'agent', NULL),
  (9,  'me', '准备与周远 1:1 的议题', 'suggested', 'task', 'P2', current_date, '16:30 前', pg_temp.at(0, '16:30'),
       '周远想聊 demo 范围，1:1 在 16:30', 403, 'agent', NULL),
  (10, 'me', '评估 Agent 首发引导页需求', 'suggested', 'task', 'P2', NULL, '待定', NULL,
       '运营提的新需求，可能影响 demo 范围', 108, 'agent', NULL),
  (11, 'me', '把 v2 字段变更同步给前端组', 'done', 'task', NULL, NULL, NULL, pg_temp.at(-1, '18:00'),
       NULL, 601, 'agent', pg_temp.at(-1, '17:45')),
  (12, 'me', '和李楠确认回归用例范围', 'done', 'task', NULL, NULL, NULL, pg_temp.at(-3, '18:00'),
       NULL, 801, 'agent', pg_temp.at(-3, '15:30'));
SELECT setval(pg_get_serial_sequence('todo', 'id'), 1000);

INSERT INTO todo_candidate (owner_id, title, priority, planned_date, due_label, note, source_message_id) VALUES
  ('me', '确认弹性曲线动效在低端机上的性能', 'P1', current_date, '评审前',
   '陈思琪改了面板展开曲线，前端需要评估性能', 202);

-- —— 日程 ——
INSERT INTO calendar_event (owner_id, title, place, starts_at, ends_at, agent_note) VALUES
  ('me', '前端站会',         '线上',               pg_temp.at(0, '10:00'), pg_temp.at(0, '10:15'), NULL),
  ('me', 'AIDo 5.0 设计评审', '会议室 A3 · 线上同步', pg_temp.at(0, '14:00'), pg_temp.at(0, '15:00'),
   '会前材料已备好：交互稿 v4 共 12 处改动，3 处与前端相关'),
  ('me', '与周远 1:1',       '周远工位',           pg_temp.at(0, '16:30'), pg_temp.at(0, '17:00'), '建议议题：demo 范围与排期'),
  ('me', '提交本周周报',     '',                   pg_temp.at(0, '18:00'), NULL,                   '草稿已生成，等你确认');

-- —— 记忆 ——
INSERT INTO memory_profile_tag (owner_id, group_name, tag, position) VALUES
  ('me', '工作角色', '前端负责人', 0), ('me', '工作角色', 'AIDo 5.0', 1), ('me', '工作角色', 'Vue', 2),
  ('me', '工作角色', 'TypeScript', 3), ('me', '工作角色', '设计系统', 4),
  ('me', '常合作的人', '周远 · 产品', 0), ('me', '常合作的人', '陈思琪 · 设计', 1),
  ('me', '常合作的人', '王磊 · 后端', 2), ('me', '常合作的人', '赵一帆 · 前端', 3),
  ('me', '工作习惯', '上午写代码', 0), ('me', '工作习惯', '下午开会', 1),
  ('me', '工作习惯', '不喜欢 19:00 后的消息打扰', 2), ('me', '工作习惯', '周报周五提交', 3);

INSERT INTO memory_item (owner_id, body, source, created_at, updated_at) VALUES
  ('me', '你更习惯先看结论，再看细节；摘要控制在 3 条以内', '对话中你告诉我', pg_temp.at(-7, '10:00'), pg_temp.at(-7, '10:00')),
  ('me', '「AIDo 5.0」是你当前的最高优先级项目，截止 10 月 20 日', '来自 TODO 和项目组', pg_temp.at(-13, '10:00'), pg_temp.at(-13, '10:00')),
  ('me', '和周远沟通时，他偏好带数字的排期估算', '从私聊中归纳', pg_temp.at(-15, '10:00'), pg_temp.at(-15, '10:00')),
  ('me', '每周二、四下午 2 点后是固定的评审时间', '来自日历', pg_temp.at(-22, '10:00'), pg_temp.at(-22, '10:00'));

INSERT INTO memory_source (owner_id, key, label, description, enabled, position) VALUES
  ('me', 'chat',     '消息',   '私聊与群聊',         true,  0),
  ('me', 'todo',     'TODO',   '小A整理出的待办',   true,  1),
  ('me', 'calendar', '日历',   '会议与日程',         true,  2),
  ('me', 'docs',     '云文档', '你创建和编辑的文档', true,  3),
  ('me', 'mail',     '邮箱',   '工作邮箱',           false, 4);

-- —— 小A ——
INSERT INTO agent_capability (owner_id, key, title, description, enabled, position) VALUES
  ('me', 'unread_digest',   '未读消息整理', '每天 8:30 汇总未读，只把需要你的挑出来',       true,  0),
  ('me', 'todo_extraction', '待办自动提取', '群聊里被 @ 或被分派的事项，自动建成 TODO',     true,  1),
  ('me', 'meeting_prep',    '会前材料准备', '会议开始前 1 小时，汇总相关文档和讨论',         true,  2),
  ('me', 'weekly_report',   '周报草拟',     '每周五 11:00 根据 TODO 和提交记录生成初稿',     false, 3);

INSERT INTO agent_quick_prompt (owner_id, body, position) VALUES
  ('me', '总结当前会话', 0), ('me', '今天有哪些会', 1), ('me', '帮我起草回复', 2), ('me', '我的 TODO 怎么排', 3);

INSERT INTO agent_feed_item (owner_id, type, payload, created_at) VALUES
  ('me', 'status', '{"text": "任务已完成：整理早间未读消息"}', pg_temp.at(0, '08:30')),
  ('me', 'agent', $${
    "paragraphs": ["早上好，林舟。你离线后有 58 条新消息，我挑出了真正需要你的："],
    "bullets": [
      {"strong": "周远在项目组 @你", "text": "，问 Agent 外壳 demo 周五能否给到——这是今天最要紧的。", "link": "/chat/c1"},
      {"strong": "赵一帆的 PR", "text": " 等你 review 已经 1 天，我放进了今天的 TODO。", "link": "/todo"},
      {"strong": "陈思琪更新了交互稿 v4", "text": "，14:00 评审前最好过一遍。", "link": "/chat/c2"}
    ],
    "tail": "其余 55 条是日常同步，放在下面的摘要里了。"
  }$$, pg_temp.at(0, '08:30')),
  ('me', 'doc', '{"title": "今日未读摘要", "desc": "5 个会话 · 58 条消息 · 3 条需要你处理", "to": "/chat/c1"}', pg_temp.at(0, '08:31')),
  ('me', 'proposal', '{"text": "另外我从消息里还识别出 2 件你今天大概率要做的事，要加进 TODO 吗？", "todoIds": [8, 9]}', pg_temp.at(0, '08:31')),
  ('me', 'user', '{"text": "下午评审我需要重点看什么？"}', pg_temp.at(0, '11:10')),
  ('me', 'agent', $${
    "paragraphs": ["交互稿 v4 相比 v3 有 12 处改动，和前端实现最相关的是这 3 处："],
    "bullets": [
      {"strong": "Agent 面板展开/收起", "text": " 改成了弹性曲线，需要确认低端机上的性能。"},
      {"strong": "消息卡片", "text": " 新增了「来自任务」的溯源行，需要后端返回 source 字段。"},
      {"strong": "TODO 看板", "text": " 卡片上加了 Agent 创建标记，和现有标签体系要统一。"}
    ],
    "tail": "要我把这 3 处整理成评审提问清单吗？"
  }$$, pg_temp.at(0, '11:10'));

-- —— 搜索历史 ——
INSERT INTO search_history (owner_id, query, searched_at) VALUES
  ('me', 'Agent demo', now()), ('me', '周远', now() - interval '1 minute'), ('me', '交互稿', now() - interval '2 minutes');
