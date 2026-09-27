-- 助理改名为「小A」：已经灌过演示数据的库里，把存量文案中的旧名字换掉。
-- 旧名字用 Unicode 转义写（U+5C0F U+96C0），代码里不再出现它；新库在 V100 里已经是新名字，这里什么也不改。
DO $$
DECLARE
  old_name constant text := U&'\5C0F\96C0';
BEGIN
  UPDATE agent_feed_item SET payload = replace(payload::text, old_name, '小A')::jsonb WHERE strpos(payload::text, old_name) > 0;
  UPDATE memory_source SET description = replace(description, old_name, '小A') WHERE strpos(description, old_name) > 0;
  UPDATE agent_capability SET description = replace(description, old_name, '小A') WHERE strpos(description, old_name) > 0;
  UPDATE todo SET note = replace(note, old_name, '小A') WHERE strpos(note, old_name) > 0;
  UPDATE todo_candidate SET note = replace(note, old_name, '小A') WHERE strpos(note, old_name) > 0;
  UPDATE memory_item SET body = replace(body, old_name, '小A') WHERE strpos(body, old_name) > 0;
  UPDATE agent_quick_prompt SET body = replace(body, old_name, '小A') WHERE strpos(body, old_name) > 0;
END $$;
