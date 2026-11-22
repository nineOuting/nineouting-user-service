-- 설정 › 프로필 "마지막 변경" 날짜 (피그마 프론트 Profile_Modal). 기존 사용자는 가입 시각으로 채운다
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP(6) NULL;
UPDATE users SET password_changed_at = created_at;
ALTER TABLE users ALTER COLUMN password_changed_at SET NOT NULL;
