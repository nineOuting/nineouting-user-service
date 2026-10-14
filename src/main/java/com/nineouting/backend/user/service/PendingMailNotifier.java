package com.nineouting.backend.user.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 이벤트 발행(공통 모듈 v0.2.0 outbox)을 붙이기 전까지 local 외 환경에서 쓰는 자리 표시용 구현.
 * 코드·링크는 로그에 남기지 않는다.
 */
@Slf4j
@Component
@Profile("!local")
public class PendingMailNotifier implements MailNotifier {

    @Override
    public void sendVerificationCode(String email, String code) {
        log.warn("메일 발송 이벤트가 아직 연결되지 않았습니다: 인증 코드 ({})", email);
    }

    @Override
    public void sendPasswordResetLink(String email, String resetLink) {
        log.warn("메일 발송 이벤트가 아직 연결되지 않았습니다: 비밀번호 재설정 ({})", email);
    }
}
