package com.alibou.whatsappclone.presence;

import com.alibou.whatsappclone.user.UserRepository;
import com.alibou.whatsappclone.ws.WebSocketConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authenticates STOMP sessions over /ws and tracks connection state:
 * - CONNECT      -> validate the Bearer token from the STOMP Authorization
 *                   header via the shared {@link JwtDecoder}, verify the
 *                   subject exists in the local users table (ADR-0012), attach
 *                   the resulting {@link JwtAuthenticationToken} as the session
 *                   user and mark the user online (Redis TTL key)
 * - DISCONNECT   -> mark offline
 * - any frame    -> re-check the session token's {@code exp} with the same
 *                   clock skew the shared decoder applies at CONNECT
 *                   (ADR-0013), then sliding refresh of the TTL + re-attach
 *                   the session user
 *
 * Why here and not spring-security-messaging: STOMP-over-SockJS cannot send
 * custom headers on the HTTP handshake, so the browser passes the JWT as an
 * Authorization header on the CONNECT frame instead. Spring Security 6.4's
 * @EnableWebSocketSecurity moved to handshake-level authentication and no
 * longer supports CONNECT-frame tokens, so the identity is established in
 * this interceptor and re-attached to every subsequent frame.
 *
 * The frame is mutated through the live StompHeaderAccessor (obtained via
 * MessageHeaderAccessor#getAccessor, as StompSubProtocolHandler does) - a
 * fresh StompHeaderAccessor#wrap is only a defensive fallback, because it
 * copies the headers and changes made to it never reach the handlers. The
 * identity travels in the standard "simpUser" header; WebSocketConfig also
 * registers spring-security-messaging's SecurityContextChannelInterceptor
 * (after this one) so the identity lands in the SecurityContextHolder,
 * which is what @AuthenticationPrincipal resolves in Spring Security 6.4.
 */
@Component
@RequiredArgsConstructor
public class UserPresenceChannelInterceptor implements ChannelInterceptor, Ordered {

    static final String SESSION_AUTH_KEY = "PRESENCE_AUTH_TOKEN";

    private final PresenceService presenceService;
    private final JwtDecoder jwtDecoder;
    private final UserRepository userRepository;

    /**
     * Per-frame expiry gate (ADR-0013). The default constructor applies the
     * same 60 s clock skew ({@link JwtTimestampValidator#DEFAULT_MAX_CLOCK_SKEW})
     * that the auto-configured shared {@link JwtDecoder} uses at CONNECT, so an
     * expired session token behaves identically on both paths. Rejects with a
     * {@link MessageDeliveryException} so the STOMP ERROR frame + session close
     * matches the CONNECT-time rejection shape.
     */
    private final JwtTimestampValidator jwtTimestampValidator = new JwtTimestampValidator();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }
        if (accessor.getCommand() == null) {
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT -> authenticate(accessor, message);
            case DISCONNECT -> {
                JwtAuthenticationToken token = sessionToken(accessor);
                if (token != null) {
                    presenceService.markOffline(token.getName());
                    enforceNotExpired(accessor, message, token);
                }
            }
            default -> {
                JwtAuthenticationToken token = sessionToken(accessor);
                if (token != null) {
                    enforceNotExpired(accessor, message, token);
                    presenceService.refresh(token.getName());
                    if (accessor.getUser() == null) {
                        accessor.setUser(token);
                    }
                }
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor, Message<?> message) {
        String authHeader = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new MessageDeliveryException(message, "STOMP CONNECT requires an Authorization: Bearer <jwt> header");
        }
        try {
            Jwt jwt = jwtDecoder.decode(authHeader.substring(7));
            ensureLocalUserExists(jwt, message);
            JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
            accessor.setUser(token);
            if (accessor.getSessionAttributes() != null) {
                accessor.getSessionAttributes().put(SESSION_AUTH_KEY, token);
            }
            presenceService.setOnline(token.getName());
        } catch (JwtException ex) {
            throw new MessageDeliveryException(message, ex);
        }
    }

    /**
     * ADR-0012: the CONNECT is accepted only for subjects known to the local
     * users table. Reuses the same lookup the REST-side {@code UserSynchronizer}
     * performs ({@link UserRepository#findByEmail}), so the WS path applies the
     * same account-existence guarantee as the REST path. Rejection happens
     * before {@code PresenceService.setOnline} and before CONNECTED is emitted;
     * the ERROR frame closes the session.
     */
    private void ensureLocalUserExists(Jwt jwt, Message<?> message) {
        String email = jwt.getClaimAsString("email");
        boolean known = email != null && userRepository.findByEmail(email).isPresent();
        if (!known) {
            throw new MessageDeliveryException(message,
                    "STOMP CONNECT rejected: user " + jwt.getSubject() + " is not registered in the local users table");
        }
    }

    /**
     * ADR-0013: per-frame expiry gate. Uses the same {@link JwtTimestampValidator}
     * semantics (including the default 60 s clock skew) as the CONNECT-time
     * decode, so a frame is rejected with the same ERROR + session close
     * (CloseStatus.PROTOCOL_ERROR) as an expired CONNECT.
     */
    private void enforceNotExpired(StompHeaderAccessor accessor, Message<?> message, JwtAuthenticationToken token) {
        OAuth2TokenValidatorResult result = jwtTimestampValidator.validate(token.getToken());
        if (result.hasErrors()) {
            throw new MessageDeliveryException(message, "STOMP session token expired");
        }
    }

    private JwtAuthenticationToken sessionToken(StompHeaderAccessor accessor) {
        return accessor.getSessionAttributes() != null
                ? (JwtAuthenticationToken) accessor.getSessionAttributes().get(SESSION_AUTH_KEY)
                : null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
