package com.nineouting.backend.user.controller;

import com.nineouting.backend.common.web.response.ApiResponse;
import com.nineouting.backend.user.dto.SignupRequest;
import com.nineouting.backend.user.dto.SignupResponse;
import com.nineouting.backend.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REQ-218 관리자 빠른 작업 (admin-service만 부른다). Gateway에서 외부로 열지 않는다.
 * 입력 규칙은 가입과 같다 (이메일 형식, 비밀번호 규칙, 이름 길이)
 */
@RestController
@RequestMapping("/internal/admin/users")
@RequiredArgsConstructor
public class InternalAdminUserController {

    private final AuthService authService;

    /** 이메일 인증 없이 계정을 만든다 */
    @PostMapping
    public ResponseEntity<ApiResponse<SignupResponse>> create(@Valid @RequestBody SignupRequest request) {
        Long userId = authService.createByAdmin(request.email(), request.password(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(new SignupResponse(userId)));
    }
}
