package com.alibou.whatsappclone.ws;

import com.alibou.whatsappclone.presence.PresenceService;
import com.alibou.whatsappclone.presence.UserPresenceChannelInterceptor;
import com.alibou.whatsappclone.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Check: two concurrent WebSocket sessions on the shared STOMP inbound
 * channel must never observe each other's principal.
 * <p>
 * The STOMP client inbound channel is an {@link ExecutorSubscribableChannel}
 * backed by a thread pool. {@link SecurityContextChannelInterceptor} relies on
 * a per-thread stack save/restore of the {@link SecurityContextHolder} around
 * each message; if that restore ever failed or the wrong context was applied,
 * a frame from session A could execute with session B's principal - a
 * cross-user data leak. This test wires the real interceptors exactly like
 * {@link WebSocketConfig} (presence first, then the SecurityContext bridge)
 * onto a real multi-threaded channel and fires thousands of interleaved
 * frames from two sessions, asserting that what the handler thread sees in
 * the SecurityContextHolder always matches the frame's own session.
 */
@ExtendWith(MockitoExtension.class)
class SecurityContextBridgeConcurrencyTest {

    @Mock
    private PresenceService presenceService;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private UserRepository userRepository;

    @Test
    void twoConcurrentSessionsOnSharedStompPoolNeverLeakPrincipals() throws Exception {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(4);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(10_000);
        pool.setThreadNamePrefix("clientInbound-test-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.initialize();

        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel(pool);
        channel.addInterceptor(new UserPresenceChannelInterceptor(presenceService, jwtDecoder, userRepository));
        channel.addInterceptor(new SecurityContextChannelInterceptor());

        JwtAuthenticationToken alice = sessionToken("alice");
        JwtAuthenticationToken bob = sessionToken("bob");
        Map<String, JwtAuthenticationToken> sessions = Map.of(
                "session-alice", alice,
                "session-bob", bob);

        Map<String, List<String>> observed = new ConcurrentHashMap<>();
        AtomicInteger handlerInvocations = new AtomicInteger();
        AtomicInteger handlerErrors = new AtomicInteger();
        AtomicReference<Throwable> firstHandlerError = new AtomicReference<>();
        MessageHandler handler = message -> {
            try {
                String sessionId = SimpMessageHeaderAccessor
                        .getAccessor(message, SimpMessageHeaderAccessor.class)
                        .getSessionId();
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                observed.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
                        .add(auth == null ? "<NONE>" : auth.getName());
                handlerInvocations.incrementAndGet();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (Throwable t) {
                handlerErrors.incrementAndGet();
                firstHandlerError.compareAndSet(null, t);
            }
        };
        channel.subscribe(handler);

        int framesPerSession = 1000;
        int total = framesPerSession * 2;
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger sendFailures = new AtomicInteger();
        AtomicReference<Throwable> firstSendFailure = new AtomicReference<>();
        ExecutorService producers = Executors.newFixedThreadPool(8);
        for (int i = 0; i < total; i++) {
            final int idx = i;
            producers.execute(() -> {
                try {
                    String sessionId = idx % 2 == 0 ? "session-alice" : "session-bob";
                    channel.send(frameMessage(sessionId, sessions.get(sessionId)));
                } catch (Throwable t) {
                    sendFailures.incrementAndGet();
                    firstSendFailure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        boolean dispatchedAll = done.await(30, TimeUnit.SECONDS);
        producers.shutdown();
        pool.shutdown();
        boolean drained = pool.getThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS);

        assertThat(dispatchedAll && drained)
                .as("dispatchedAll=%s drained=%s sendFailures=%s handlerErrors=%s",
                        dispatchedAll, drained, sendFailures.get(), handlerErrors.get())
                .isTrue();
        assertThat(handlerInvocations.get()).isEqualTo(total);
        assertThat(observed.get("session-alice")).containsOnly("alice");
        assertThat(observed.get("session-bob")).containsOnly("bob");
        assertThat(observed.get("session-alice")).doesNotContain("bob", "<NONE>");
        assertThat(observed.get("session-bob")).doesNotContain("alice", "<NONE>");
    }

    private static JwtAuthenticationToken sessionToken(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private static Message<byte[]> frameMessage(String sessionId, JwtAuthenticationToken token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(sessionId);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("PRESENCE_AUTH_TOKEN", token);
        accessor.setSessionAttributes(attributes);
        accessor.setLeaveMutable(true);
        return new GenericMessage<>(new byte[0], accessor.getMessageHeaders());
    }
}
