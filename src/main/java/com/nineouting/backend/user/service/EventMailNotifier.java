package com.nineouting.backend.user.service;

import com.nineouting.backend.common.event.user.EmailVerificationRequested;
import com.nineouting.backend.common.event.user.PasswordResetRequested;
import com.nineouting.backend.common.outbox.OutboxEventWriter;
import com.nineouting.backend.user.config.UserServiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 메일 발송 요청을 이벤트로 남긴다. notification-service가 받아서 메일을 보낸다.
 * 이벤트는 outbox에 기록되므로 호출하는 쪽은 트랜잭션 안에 있어야 한다.
 * 로컬 개발에서는 notification-service 없이도 확인할 수 있도록 코드·링크를 로그에도 남긴다.
 */
@Slf4j
@Component
public class EventMailNotifier implements MailNotifier {

    private final OutboxEventWriter outboxEventWriter;
    private final boolean logForLocalDev;

    public EventMailNotifier(OutboxEventWriter outboxEventWriter, UserServiceProperties properties) {
        this.outboxEventWriter = outboxEventWriter;
        this.logForLocalDev = properties.logMailForLocalDev();
    }

    @Override
    public void sendVerificationCode(String email, String code) {
        outboxEventWriter.append(EmailVerificationRequested.TOPIC, email, EmailVerificationRequested.TYPE,
                new EmailVerificationRequested(email, code));
        if (logForLocalDev) {
            log.info("[로컬 메일] {} 인증 코드: {}", email, code);
        }
    }

    @Override
    public void sendPasswordResetLink(String email, String resetLink) {
        outboxEventWriter.append(PasswordResetRequested.TOPIC, email, PasswordResetRequested.TYPE,
                new PasswordResetRequested(email, resetLink));
        if (logForLocalDev) {
            log.info("[로컬 메일] {} 비밀번호 재설정 링크: {}", email, resetLink);
        }
    }
}
