package com.alibou.whatsappclone.ws;

import com.alibou.whatsappclone.message.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/**
 * Inbound STOMP destinations (client -> server over /app/*).
 * The caller's identity comes from the authenticated STOMP session (attached by
 * {@link com.alibou.whatsappclone.presence.UserPresenceChannelInterceptor} to
 * every frame, bridged into the SecurityContextHolder by the registered
 * SecurityContextChannelInterceptor), never from the payload - spoofing
 * another user's identity is impossible.
 */
@Controller
@RequiredArgsConstructor
public class RealtimeController {

    private final TypingService typingService;
    private final MessageService messageService;

    @MessageMapping("/typing")
    public void typing(@Payload TypingRequest request, @AuthenticationPrincipal Jwt jwt) {
        typingService.relayTyping(request.conversationId(), jwt.getSubject(), request.typing());
    }

    @MessageMapping("/message-ack")
    public void acknowledge(@Payload MessageAckRequest request, @AuthenticationPrincipal Jwt jwt) {
        messageService.acknowledgeDelivered(request.messageId(), jwt.getSubject());
    }
}
