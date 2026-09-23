-- 取消旧的请求去重字段，重复提问也应保存为独立轮次。
DROP INDEX IF EXISTS chat_turn_request_idx;
ALTER TABLE chat_turn DROP COLUMN IF EXISTS request_id;
