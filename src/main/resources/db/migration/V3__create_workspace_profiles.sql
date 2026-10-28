-- REQ-114·115·118 (2026-10-28 기획서): 워크스페이스마다 이름·프로필 사진·소속을 따로 정할 수 있다.
-- 비운 칸(NULL)은 계정 프로필(users)을 쓴다. 워크스페이스에서 나가도 지우지 않는다 (다시 들어오면 그대로).
CREATE TABLE workspace_profiles
(
    id                BIGINT       GENERATED ALWAYS AS IDENTITY,
    user_id           BIGINT       NOT NULL,
    workspace_id      BIGINT       NOT NULL,
    name              VARCHAR(50)  NULL,
    affiliation       VARCHAR(100) NULL,
    profile_image_key VARCHAR(255) NULL,
    created_at        TIMESTAMP(6) NOT NULL,
    updated_at        TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_workspace_profiles UNIQUE (user_id, workspace_id)
);
CREATE INDEX idx_workspace_profiles_workspace ON workspace_profiles (workspace_id);
