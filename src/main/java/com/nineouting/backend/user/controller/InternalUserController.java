package com.nineouting.backend.user.controller;

import com.nineouting.backend.common.web.response.ApiResponse;
import com.nineouting.backend.user.dto.UserSummaryResponse;
import com.nineouting.backend.user.service.UserProfileService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 조회용. Gateway에서 외부로 열지 않는다 (docs/services/user-service.md 4.3).
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserProfileService userProfileService;

    @GetMapping
    public ApiResponse<List<UserSummaryResponse>> findUsers(@RequestParam List<Long> ids,
                                                            @RequestParam(required = false) Long workspaceId) {
        return ApiResponse.ok(userProfileService.findSummaries(ids, workspaceId));
    }
}
