package com.nineouting.backend.user.service;

/**
 * 메일은 notification-service가 보낸다. user-service는 보내 달라는 요청(이벤트)만 낸다.
 */
public interface MailNotifier {

    void sendVerificationCode(String email, String code);

    void sendPasswordResetLink(String email, String resetLink);
}
