package com.nineouting.backend.user.dto;

/**
 * @param expiresIn access 토큰 유효 시간(초). refresh 토큰은 HttpOnly 쿠키로 따로 전달한다.
 */
public record TokenResponse(String accessToken, long expiresIn) {
}
