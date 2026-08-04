package com.alibou.whatsappclone.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSynchronizer {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Value("${application.cache.user-sync-ttl-seconds:60}")
    private long syncTtlSeconds;

    /**
     * Keeps DB writes bounded: each user is synced at most once per TTL window,
     * instead of hitting the database on every single request.
     */
    private final Cache<String, Instant> lastSyncBySubject = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();

    public void synchronizeWithIdp(Jwt token) {
        Optional<String> subject = Optional.ofNullable(token.getSubject());
        if (subject.isEmpty()) {
            return;
        }

        Instant lastSync = lastSyncBySubject.getIfPresent(subject.get());
        if (lastSync != null && lastSync.plusSeconds(syncTtlSeconds).isAfter(Instant.now())) {
            return;
        }

        getUserEmail(token).ifPresent(userEmail -> {
            Optional<User> existingUser = userRepository.findByEmail(userEmail);
            User user = userMapper.fromTokenAttributes(token.getClaims());
            existingUser.ifPresent(value -> user.setId(value.getId()));
            userRepository.save(user);
            lastSyncBySubject.put(subject.get(), Instant.now());
            log.debug("Synchronized user {}", userEmail);
        });
    }

    private Optional<String> getUserEmail(Jwt token) {
        Map<String, Object> attributes = token.getClaims();
        if (attributes.containsKey("email")) {
            return Optional.of(attributes.get("email").toString());
        }
        return Optional.empty();
    }
}
