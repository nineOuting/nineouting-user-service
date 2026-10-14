package com.nineouting.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterProfileImageRequest(
        @NotBlank(message = "imageKey를 보내 주세요")
        String imageKey
) {
}
