DROP INDEX IF EXISTS chat_turn_request_idx;
ALTER TABLE chat_turn DROP COLUMN IF EXISTS request_id;
