package com.nineouting.backend.user;

import com.nineouting.backend.user.service.MailNotifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 MySQL·Redis를 Docker로 띄우고 접속 정보를 자동으로 연결한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    @Bean
    @Primary
    CapturingMailNotifier capturingMailNotifier() {
        return new CapturingMailNotifier();
    }

    /** 보낸 인증 코드·재설정 링크를 테스트에서 꺼내 쓴다. */
    public static class CapturingMailNotifier implements MailNotifier {

        private final Map<String, String> codes = new ConcurrentHashMap<>();
        private final Map<String, String> resetLinks = new ConcurrentHashMap<>();

        @Override
        public void sendVerificationCode(String email, String code) {
            codes.put(email, code);
        }

        @Override
        public void sendPasswordResetLink(String email, String resetLink) {
            resetLinks.put(email, resetLink);
        }

        public String codeFor(String email) {
            return codes.get(email);
        }

        public String resetLinkFor(String email) {
            return resetLinks.get(email);
        }
    }
}
