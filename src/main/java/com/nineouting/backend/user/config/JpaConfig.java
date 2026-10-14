package com.nineouting.backend.user.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * web-common의 BaseTimeEntity(created_at, updated_at)가 동작하도록 JPA Auditing을 켠다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
