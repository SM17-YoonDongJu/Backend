ALTER TABLE user_claims ALTER COLUMN offered_amount TYPE bigint;
ALTER TABLE user_claims DROP COLUMN diagnosis;
ALTER TABLE user_claims DROP COLUMN hospitalization;
ALTER TABLE user_claims ADD  COLUMN details jsonb;
ALTER TABLE user_claims ADD  COLUMN question text;
ALTER TABLE user_claims ADD  COLUMN updated_at timestamp;
