package com.nineouting.backend.user.dto;

import java.util.Locale;

/**
 * 이메일은 대소문자를 구분하지 않으므로 앞뒤 공백을 지우고 소문자로 저장·비교한다.
 */
final class Emails {

    private Emails() {
    }

    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
