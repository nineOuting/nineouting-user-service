package com.nineouting.backend.user;

import com.nineouting.backend.user.service.EventMailNotifier;
import com.nineouting.backend.user.service.MailNotifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 PostgreSQL·Redis·Kafka·S3(LocalStack)를 Docker로 띄우고 접속 정보를 자동으로 연결한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String TEST_BUCKET = "test-bucket";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17.6-alpine");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
    }

    @Bean
    LocalStackContainer localStackContainer() {
        return new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4")).withServices("s3");
    }

    /** LocalStack S3 접속 정보를 설정에 넣고 테스트용 버킷을 만든다. */
    @Bean
    DynamicPropertyRegistrar storageProperties(LocalStackContainer localStack) {
        return registry -> {
            try {
                localStack.execInContainer("awslocal", "s3", "mb", "s3://" + TEST_BUCKET);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            registry.add("nineouting.user.storage.bucket", () -> TEST_BUCKET);
            registry.add("nineouting.user.storage.endpoint", () -> localStack.getEndpoint().toString());
            registry.add("nineouting.user.storage.region", localStack::getRegion);
            registry.add("nineouting.user.storage.access-key", localStack::getAccessKey);
            registry.add("nineouting.user.storage.secret-key", localStack::getSecretKey);
        };
    }

    @Bean
    @Primary
    CapturingMailNotifier capturingMailNotifier(EventMailNotifier eventMailNotifier) {
        return new CapturingMailNotifier(eventMailNotifier);
    }

    /** 실제 이벤트 발행은 그대로 하면서, 보낸 인증 코드·재설정 링크를 테스트에서 꺼내 쓴다. */
    public static class CapturingMailNotifier implements MailNotifier {

        private final MailNotifier delegate;
        private final Map<String, String> codes = new ConcurrentHashMap<>();
        private final Map<String, String> resetLinks = new ConcurrentHashMap<>();

        CapturingMailNotifier(MailNotifier delegate) {
            this.delegate = delegate;
        }

        @Override
        public void sendVerificationCode(String email, String code) {
            delegate.sendVerificationCode(email, code);
            codes.put(email, code);
        }

        @Override
        public void sendPasswordResetLink(String email, String resetLink) {
            delegate.sendPasswordResetLink(email, resetLink);
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
