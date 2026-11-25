package com.nineouting.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nineouting.backend.user.dto.UserSummaryResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Redis가 멈춰도 조회는 DB로 이어지고, 지우기는 커밋 뒤에 한다 */
class UserSummaryCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final UserSummaryCache cache = new UserSummaryCache(redis, JsonMapper.builder().build());

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void Redis가_안_되면_빈_결과를_주고_넣기도_조용히_넘어간다() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.executePipelined(any(SessionCallback.class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get(List.of(1L, 2L))).isEmpty();
        assertThatCode(() -> cache.put(List.of(new UserSummaryResponse(1L, "a", "a@x", null, null))))
                .doesNotThrowAnyException();
    }

    @Test
    void 빈_목록은_Redis를_부르지_않는다() {
        assertThat(cache.get(List.of())).isEmpty();
        cache.put(List.of());
        verify(redis, never()).opsForValue();
    }

    @Test
    void 트랜잭션_안에서는_커밋_뒤에_지운다() {
        TransactionSynchronizationManager.initSynchronization();
        cache.evictAfterCommit(7L);
        verify(redis, never()).delete("user-summary:7");

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(redis).delete("user-summary:7");
    }

    @Test
    void 트랜잭션_밖에서는_바로_지우고_실패해도_넘어간다() {
        when(redis.delete("user-summary:8")).thenThrow(new RedisConnectionFailureException("down"));
        assertThatCode(() -> cache.evictAfterCommit(8L)).doesNotThrowAnyException();
        verify(redis).delete("user-summary:8");
    }
}
