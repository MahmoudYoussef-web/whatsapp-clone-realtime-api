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

// handles WS auth: checks Bearer on CONNECT, tracks online/offline in redis
// sockjs can't send headers on handshake so token comes on CONNECT frame
@Component
@RequiredArgsConstructor
public class UserPresenceChannelInterceptor implements ChannelInterceptor, Ordered {

    static final String SESSION_AUTH_KEY = "PRESENCE_AUTH_TOKEN";

    private final PresenceService presenceService;
    private final JwtDecoder jwtDecoder;
    private final UserRepository userRepository;

    // same clock skew as decoder so CONNECT and frames behave same
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

    // only allow CONNECT if user exists locally, else reject
    private void ensureLocalUserExists(Jwt jwt, Message<?> message) {
        String email = jwt.getClaimAsString("email");
        boolean known = email != null && userRepository.findByEmail(email).isPresent();
        if (!known) {
            throw new MessageDeliveryException(message,
                    "STOMP CONNECT rejected: user " + jwt.getSubject() + " is not registered in the local users table");
        }
    }

    // check token not expired on each frame
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
        // must run before SecurityContextChannelInterceptor
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
