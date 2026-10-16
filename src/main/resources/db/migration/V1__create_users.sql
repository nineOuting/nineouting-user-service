CREATE TABLE users
(
    id                BIGINT       GENERATED ALWAYS AS IDENTITY,
    email             VARCHAR(255) NOT NULL,
    password_hash     VARCHAR(100) NOT NULL,
    name              VARCHAR(50)  NOT NULL,
    affiliation       VARCHAR(100) NULL,
    profile_image_key VARCHAR(255) NULL,
    created_at        TIMESTAMP(6) NOT NULL,
    updated_at        TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);
