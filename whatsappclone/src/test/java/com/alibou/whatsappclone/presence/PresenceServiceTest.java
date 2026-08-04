package com.alibou.whatsappclone.presence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PresenceService presenceService;

    @BeforeEach
    void setUp() {
        presenceService = new PresenceService(redisTemplate);
        presenceService.setOnlineTtlSeconds(60);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void setOnline_writesKeyWithSlidingTtl() {
        presenceService.setOnline("alice");

        verify(valueOperations).set("presence:user:alice", "1", Duration.ofSeconds(60));
    }

    @Test
    void isOnline_returnsKeyExistence() {
        when(redisTemplate.hasKey("presence:user:alice")).thenReturn(true);
        assertThat(presenceService.isOnline("alice")).isTrue();

        when(redisTemplate.hasKey("presence:user:bob")).thenReturn(false);
        assertThat(presenceService.isOnline("bob")).isFalse();
    }

    @Test
    void markOffline_deletesKey() {
        presenceService.markOffline("alice");

        verify(redisTemplate).delete("presence:user:alice");
    }

    @Test
    void refresh_renewsTtl() {
        presenceService.refresh("alice");

        verify(redisTemplate).expire("presence:user:alice", Duration.ofSeconds(60));
    }

    @Test
    void isOnline_degradesToOfflineWhenRedisIsDown() {
        when(redisTemplate.hasKey(any())).thenThrow(new RuntimeException("connection refused"));

        assertThat(presenceService.isOnline("alice")).isFalse();
    }

    @Test
    void setOnline_swallowsRedisFailures() {
        doThrow(new RuntimeException("connection refused"))
                .when(valueOperations).set(any(), any(), any());

        presenceService.setOnline("alice"); // must not throw
    }

    @Test
    void ttlClampedToAtLeastOneSecond() {
        presenceService.setOnlineTtlSeconds(-5);

        presenceService.setOnline("alice");
        verify(valueOperations).set("presence:user:alice", "1", Duration.ofSeconds(1));
    }

    @Test
    void defaultTtlIsUsedWhenValueNotConfigured() {
        ReflectionTestUtils.setField(presenceService, "onlineTtl", Duration.ofSeconds(60));
        presenceService.setOnline("alice");
        verify(valueOperations).set("presence:user:alice", "1", Duration.ofSeconds(60));
    }
}
