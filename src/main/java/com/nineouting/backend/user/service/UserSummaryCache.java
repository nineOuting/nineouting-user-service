package com.nineouting.backend.user.service;

import com.nineouting.backend.user.dto.UserSummaryResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 다른 서비스가 사람을 보여 줄 때 부르는 사용자 요약(이름, 메일, 사진, 소속)을 Redis에 잠깐 둔다 (2026-11-25 부하 시험 후속).
 * 프로필이 바뀌면 커밋 뒤 지운다. Redis가 안 되면 캐시 없이 DB에서 읽는다 (docs/redis-usage.md)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSummaryCache {

    private static final String KEY = "user-summary:";
    /** 사진 주소(presigned, 1시간)가 화면에서 쓰이는 동안 유효하도록 짧게 둔다 */
    static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    /** 캐시에 있는 사용자만 돌려준다 */
    public Map<Long, UserSummaryResponse> get(Collection<Long> userIds) {
        Map<Long, UserSummaryResponse> found = new HashMap<>();
        if (userIds.isEmpty()) {
            return found;
        }
        List<Long> ids = List.copyOf(userIds);
        try {
            List<String> values = redis.opsForValue().multiGet(ids.stream().map(UserSummaryCache::key).toList());
            for (int i = 0; values != null && i < ids.size(); i++) {
                if (values.get(i) != null) {
                    found.put(ids.get(i), jsonMapper.readValue(values.get(i), UserSummaryResponse.class));
                }
            }
        } catch (DataAccessException | JacksonException e) {
            log.warn("사용자 요약 캐시를 읽지 못해 DB에서 읽습니다: {}", e.getMessage());
            return new HashMap<>();
        }
        return found;
    }

    public void put(Collection<UserSummaryResponse> users) {
        if (users.isEmpty()) {
            return;
        }
        try {
            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                    users.forEach(user -> ops.opsForValue().set(key(user.userId()), jsonMapper.writeValueAsString(user), TTL));
                    return null;
                }
            });
        } catch (DataAccessException | JacksonException e) {
            log.warn("사용자 요약 캐시에 넣지 못했습니다: {}", e.getMessage());
        }
    }

    /** 프로필이 바뀌면 커밋 뒤에 지운다 (커밋 전에 지우면 그사이 다른 요청이 예전 값을 다시 넣을 수 있다) */
    public void evictAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(userId);
            }
        });
    }

    private void evict(Long userId) {
        try {
            redis.delete(key(userId));
        } catch (DataAccessException e) {
            // 지우지 못해도 TTL 뒤에는 새 값이 보인다
            log.warn("사용자 요약 캐시를 지우지 못했습니다 (userId={}): {}", userId, e.getMessage());
        }
    }

    private static String key(Long userId) {
        return KEY + userId;
    }
}
