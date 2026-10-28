package com.nineouting.backend.user;

import com.nineouting.backend.common.event.user.EmailVerificationRequested;
import com.nineouting.backend.common.event.user.PasswordResetRequested;
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
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * 테스트용 PostgreSQL·Redis·LocalStack(S3·SNS·SQS)을 Docker로 띄우고 접속 정보를 자동으로 연결한다.
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
    LocalStackContainer localStackContainer() {
        return new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.4")).withServices("s3", "sns", "sqs");
    }

    @Bean
    SnsClient snsClient(LocalStackContainer localStack) {
        return SnsClient.builder().endpointOverride(localStack.getEndpoint()).region(Region.of(localStack.getRegion()))
                .credentialsProvider(credentials(localStack)).build();
    }

    @Bean
    SqsClient sqsClient(LocalStackContainer localStack) {
        return SqsClient.builder().endpointOverride(localStack.getEndpoint()).region(Region.of(localStack.getRegion()))
                .credentialsProvider(credentials(localStack)).build();
    }

    @Bean
    LocalMessaging localMessaging(SnsClient snsClient, SqsClient sqsClient) {
        return new LocalMessaging(snsClient, sqsClient);
    }

    /** 이 서비스가 보내는 토픽을 만들고 outbox에 토픽 ARN 앞부분을 알려 준다 */
    @Bean
    DynamicPropertyRegistrar messagingProperties(LocalMessaging messaging) {
        return registry -> {
            messaging.topicArn(PasswordResetRequested.TOPIC);
            registry.add("nineouting.outbox.topic-prefix", () -> messaging.topicArnPrefix(EmailVerificationRequested.TOPIC));
        };
    }

    private static StaticCredentialsProvider credentials(LocalStackContainer localStack) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey()));
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

    /** 실제 이벤트 발행은 그대로 하면서, 보낸 인증 코드를 테스트에서 꺼내 쓴다. */
    public static class CapturingMailNotifier implements MailNotifier {

        private final MailNotifier delegate;
        private final Map<String, String> codes = new ConcurrentHashMap<>();
        private final Map<String, String> resetCodes = new ConcurrentHashMap<>();

        CapturingMailNotifier(MailNotifier delegate) {
            this.delegate = delegate;
        }

        @Override
        public void sendVerificationCode(String email, String code) {
            delegate.sendVerificationCode(email, code);
            codes.put(email, code);
        }

        @Override
        public void sendPasswordResetCode(String email, String code) {
            delegate.sendPasswordResetCode(email, code);
            resetCodes.put(email, code);
        }

        public String codeFor(String email) {
            return codes.get(email);
        }

        public String resetCodeFor(String email) {
            return resetCodes.get(email);
        }
    }
}
