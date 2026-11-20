-- REQ-116 설정 > 프로필 비밀번호 카드의 "마지막 변경 날짜" (디자인 11, 2026-11-20).
-- 가입, 비밀번호 변경, 비밀번호 재설정 때 갱신한다. 이미 있는 계정은 가입 시각으로 채운다
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP(6) NULL;
UPDATE users SET password_changed_at = created_at;
ALTER TABLE users ALTER COLUMN password_changed_at SET NOT NULL;
