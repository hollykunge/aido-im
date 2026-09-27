-- 演示数据里「今天」的消息、日程定在 08:30–18:00。如果在这之前（比如一早）灌数据，这些时间还在未来：
-- 新发的消息反而排在它们前面，会话预览也不对。这种情况下把整个演示场景挪到前一天，前后顺序保持不变。
-- TODO 的计划日期（「今天」分组）不动。
DO $$
BEGIN
  IF (SELECT max(sent_at) FROM message) > now() THEN
    UPDATE message SET sent_at = sent_at - interval '1 day';
    UPDATE conversation SET last_message_at = last_message_at - interval '1 day';
    UPDATE calendar_event SET starts_at = starts_at - interval '1 day', ends_at = ends_at - interval '1 day';
    UPDATE todo SET due_at = due_at - interval '1 day', completed_at = completed_at - interval '1 day';
    UPDATE agent_feed_item SET created_at = created_at - interval '1 day';
  END IF;
END $$;
