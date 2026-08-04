package com.alibou.whatsappclone.presence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Presence = a Redis key with a sliding TTL, refreshed on every STOMP frame the
 * user sends. A key exists -> the user has an active WebSocket connection.
 * Every call degrades gracefully: if Redis is unreachable the app keeps working
 * and simply reports everyone as offline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String KEY_PREFIX = "presence:user:";

    private final StringRedisTemplate redisTemplate;
    private Duration onlineTtl = Duration.ofSeconds(60);

    @Value("${application.presence.redis-ttl-seconds:60}")
    public void setOnlineTtlSeconds(long seconds) {
        this.onlineTtl = Duration.ofSeconds(Math.max(seconds, 1));
    }

    public void setOnline(String userId) {
        try {
            redisTemplate.opsForValue().set(key(userId), "1", onlineTtl);
        } catch (RuntimeException e) {
            log.warn("Presence: unable to mark {} online (Redis unavailable?)", userId, e);
        }
    }

    public void refresh(String userId) {
        try {
            redisTemplate.expire(key(userId), onlineTtl);
        } catch (RuntimeException e) {
            log.warn("Presence: unable to refresh {} (Redis unavailable?)", userId, e);
        }
    }

    public boolean isOnline(String userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void markOffline(String userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException e) {
            log.warn("Presence: unable to mark {} offline (Redis unavailable?)", userId, e);
        }
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
