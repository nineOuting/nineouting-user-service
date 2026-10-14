package com.nineouting.backend.user.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발용: notification-service 없이도 인증 코드·재설정 링크를 로그에서 확인한다.
 * 코드가 로그에 남으므로 local 프로필에서만 켠다.
 */
@Slf4j
@Component
@Profile("local")
public class LoggingMailNotifier implements MailNotifier {

    @Override
    public void sendVerificationCode(String email, String code) {
        log.info("[로컬 메일] {} 인증 코드: {}", email, code);
    }

    @Override
    public void sendPasswordResetLink(String email, String resetLink) {
        log.info("[로컬 메일] {} 비밀번호 재설정 링크: {}", email, resetLink);
    }
}
