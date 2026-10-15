package com.nineouting.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nineouting.backend.common.event.user.EmailVerificationRequested;
import com.nineouting.backend.common.event.user.PasswordResetRequested;
import com.nineouting.backend.common.outbox.OutboxEventRepository;
import com.nineouting.backend.user.TestcontainersConfiguration;
import com.nineouting.backend.user.TestcontainersConfiguration.CapturingMailNotifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 메일 발송 요청이 outbox를 거쳐 실제 Kafka 토픽까지 가는지 확인한다 (notification-service가 받을 메시지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MailEventPublishingTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CapturingMailNotifier mail;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Test
    void 인증_메일_요청이_Kafka_이벤트로_발행된다() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";

        postJson("/api/auth/email-verifications", "{\"email\":\"%s\"}".formatted(email));

        String message = awaitMessage(EmailVerificationRequested.TOPIC, email);
        assertThat((String) JsonPath.read(message, "$.eventType")).isEqualTo(EmailVerificationRequested.TYPE);
        assertThat((String) JsonPath.read(message, "$.source")).isEqualTo("user-service");
        assertThat((String) JsonPath.read(message, "$.eventId")).isNotBlank();
        assertThat((String) JsonPath.read(message, "$.payload.email")).isEqualTo(email);
        assertThat((String) JsonPath.read(message, "$.payload.code")).isEqualTo(mail.codeFor(email));
        awaitOutboxDeleted(email);
    }

    @Test
    void 비밀번호_재설정_요청이_Kafka_이벤트로_발행된다() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        postJson("/api/auth/email-verifications", "{\"email\":\"%s\"}".formatted(email));
        postJson("/api/auth/email-verifications/confirm", "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, mail.codeFor(email)));
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\",\"name\":\"테스터\"}".formatted(email)))
                .andExpect(status().isCreated());

        postJson("/api/auth/password-reset", "{\"email\":\"%s\"}".formatted(email));

        String message = awaitMessage(PasswordResetRequested.TOPIC, email);
        assertThat((String) JsonPath.read(message, "$.payload.resetLink")).isEqualTo(mail.resetLinkFor(email));
    }

    private void postJson(String url, String body) throws Exception {
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    /** outbox는 Kafka로 보낸 이벤트를 바로 지운다 (공통 모듈 0.3.0부터). */
    private void awaitOutboxDeleted(String email) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (outboxEventRepository.findAll().stream().noneMatch(event -> event.getPayload().contains(email))) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(email + " 이벤트가 outbox에서 지워지지 않았습니다");
    }

    /** 토픽에서 해당 이메일(메시지 키)의 메시지가 올 때까지 기다린다. */
    private String awaitMessage(String topic, String email) {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), null, null, overrides)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(WAIT);
            List<String> seenKeys = new ArrayList<>();
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (email.equals(record.key())) {
                        return record.value();
                    }
                    seenKeys.add(record.key());
                }
            }
            throw new AssertionError(topic + "에서 " + email + " 메시지를 받지 못했습니다. 받은 키: " + seenKeys);
        }
    }
}
