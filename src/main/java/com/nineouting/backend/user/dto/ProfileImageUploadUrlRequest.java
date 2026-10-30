package com.nineouting.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

public record ProfileImageUploadUrlRequest(
        @NotBlank(message = "사진 형식(contentType)을 보내 주세요")
        String contentType
) {
}
