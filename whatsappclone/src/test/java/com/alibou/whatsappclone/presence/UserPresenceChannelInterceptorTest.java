package com.alibou.whatsappclone.presence;

import com.alibou.whatsappclone.user.User;
import com.alibou.whatsappclone.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPresenceChannelInterceptorTest {

    @Mock
    private PresenceService presenceService;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserPresenceChannelInterceptor interceptor;

    private static Jwt jwt(String subject) {
        return jwt(subject, null);
    }

    private static Jwt jwt(String subject, Instant expiresAt) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("email", subject + "@wa.com");
        if (expiresAt != null) {
            builder.claim("exp", expiresAt);
        }
        return builder.build();
    }

    private StompHeaderAccessor accessor(StompCommand command, JwtAuthenticationToken sessionToken) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (sessionToken != null) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put(UserPresenceChannelInterceptor.SESSION_AUTH_KEY, sessionToken);
            accessor.setSessionAttributes(attributes);
        }
        return accessor;
    }

    private Message<byte[]> message(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return new GenericMessage<>(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connect_validatesBearerTokenAndMarksOnline() {
        Jwt jwt = jwt("alice");
        when(jwtDecoder.decode("abc.def")).thenReturn(jwt);
        when(userRepository.findByEmail("alice@wa.com")).thenReturn(Optional.of(new User()));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer abc.def");
        Map<String, Object> attributes = new HashMap<>();
        accessor.setSessionAttributes(attributes);

        Message<?> out = interceptor.preSend(message(accessor), null);

        verify(presenceService).setOnline("alice");
        assertThat(accessor.getUser()).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(attributes).containsKey(UserPresenceChannelInterceptor.SESSION_AUTH_KEY);
    }

    @Test
    void connect_withoutAuthorizationHeader_fails() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Map<String, Object> attributes = new HashMap<>();
        accessor.setSessionAttributes(attributes);

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class);
        verify(presenceService, never()).setOnline(any());
    }

    @Test
    void connect_withInvalidToken_fails() {
        when(jwtDecoder.decode("bad.token")).thenThrow(new org.springframework.security.oauth2.jwt.JwtException("invalid"));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer bad.token");
        Map<String, Object> attributes = new HashMap<>();
        accessor.setSessionAttributes(attributes);

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        verify(presenceService, never()).setOnline(any());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void connect_userNeverInLocalDb_fails() {
        when(jwtDecoder.decode("ghost.token")).thenReturn(jwt("ghost"));
        when(userRepository.findByEmail("ghost@wa.com")).thenReturn(Optional.empty());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer ghost.token");
        Map<String, Object> attributes = new HashMap<>();
        accessor.setSessionAttributes(attributes);

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("not registered in the local users table");
        verify(presenceService, never()).setOnline(any());
        assertThat(attributes).doesNotContainKey(UserPresenceChannelInterceptor.SESSION_AUTH_KEY);
    }

    @Test
    void connect_userDeletedBeforeTokenExpiry_fails() {
        when(jwtDecoder.decode("deleted.token")).thenReturn(jwt("deleted"));
        when(userRepository.findByEmail("deleted@wa.com")).thenReturn(Optional.empty());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer deleted.token");
        Map<String, Object> attributes = new HashMap<>();
        accessor.setSessionAttributes(attributes);

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("not registered in the local users table");
        verify(presenceService, never()).setOnline(any());
    }

    @Test
    void connect_tokenWithoutEmailClaim_fails() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("no-email").build();
        when(jwtDecoder.decode("noemail.token")).thenReturn(jwt);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer noemail.token");
        Map<String, Object> attributes = new HashMap<>();
        accessor.setSessionAttributes(attributes);

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("not registered in the local users table");
        verify(userRepository, never()).findByEmail(any());
        verify(presenceService, never()).setOnline(any());
    }

    @Test
    void disconnect_marksUserOffline() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt("alice"), null, "alice");
        Message<byte[]> message = message(accessor(StompCommand.DISCONNECT, token));

        interceptor.preSend(message, null);

        verify(presenceService).markOffline("alice");
    }

    @Test
    void anyFrame_refreshesPresenceAndReattachesUser() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt("alice"), null, "alice");
        StompHeaderAccessor accessor = accessor(StompCommand.MESSAGE, token);

        Message<?> out = interceptor.preSend(message(accessor), null);

        verify(presenceService).refresh("alice");
        assertThat(accessor.getUser()).isSameAs(token);
    }

    @Test
    void frameBeforeExpiry_stillRefreshesPresence() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt("alice", Instant.now().plus(10, ChronoUnit.MINUTES)), null, "alice");
        StompHeaderAccessor accessor = accessor(StompCommand.MESSAGE, token);

        interceptor.preSend(message(accessor), null);

        verify(presenceService).refresh("alice");
        assertThat(accessor.getUser()).isSameAs(token);
    }

    @Test
    void frameAfterTokenExpiry_isRejectedAndPresenceNotRefreshed() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt("alice", Instant.now().minus(10, ChronoUnit.MINUTES)), null, "alice");
        StompHeaderAccessor accessor = accessor(StompCommand.MESSAGE, token);

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("expired");
        verify(presenceService, never()).refresh(any());
    }

    @Test
    void disconnectWithExpiredToken_marksOfflineAndIsRejected() {
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt("alice", Instant.now().minus(10, ChronoUnit.MINUTES)), null, "alice");
        StompHeaderAccessor accessor = accessor(StompCommand.DISCONNECT, token);

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("expired");
        verify(presenceService).markOffline("alice");
    }

    @Test
    void unknownUserIsNotTouched() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionAttributes(new HashMap<>());

        interceptor.preSend(message(accessor), null);

        verify(presenceService, never()).setOnline(any());
        verify(presenceService, never()).refresh(any());
        verify(presenceService, never()).markOffline(any());
    }
}
