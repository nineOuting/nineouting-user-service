package com.nineouting.backend.user.dto;

/**
 * 비밀번호 규칙 (docs/services/user-service.md): 8~64자, 영문·숫자 각각 1자 이상.
 * 영문·숫자 말고는 키보드의 특수문자(ASCII)만 쓸 수 있다 (REQ-012, 2026-10-28 기획서). 공백·한글은 안 된다.
 * 64자 상한은 BCrypt가 72바이트까지만 처리하기 때문이다. 가입·변경·재설정에 같은 규칙을 쓴다.
 */
public final class PasswordPolicy {

    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d!-/:-@\\[-`{-~]{8,64}$";
    public static final String MESSAGE = "비밀번호는 8~64자로, 영문과 숫자를 각각 1자 이상 포함해야 합니다 (특수문자 사용 가능, 공백·한글 불가)";

    private PasswordPolicy() {
    }
}
