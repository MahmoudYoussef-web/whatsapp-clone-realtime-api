package com.alibou.whatsappclone.ws;

import com.alibou.whatsappclone.conversation.ConversationService;
import com.alibou.whatsappclone.notification.Notification;
import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * 1:1 voice-call signaling relay (spike). SDP/ICE travel opaquely inside the
 * notification payload — the server never parses WebRTC, it only enforces
 * that both sides belong to the same conversation and forwards to the target.
 * Media is peer-to-peer (STUN/TURN); nothing is recorded or stored.
 */
@Service
@RequiredArgsConstructor
public class CallService {

    private static final Set<NotificationType> SIGNALS = Set.of(
            NotificationType.CALL_OFFER,
            NotificationType.CALL_ANSWER,
            NotificationType.CALL_ICE,
            NotificationType.CALL_END);

    private final ConversationService conversationService;
    private final NotificationService notificationService;

    public void relaySignal(UUID conversationId, String callerId, String targetUserId,
                            NotificationType signal, String payload) {
        if (!SIGNALS.contains(signal)) {
            throw new IllegalArgumentException("Unknown call signal " + signal);
        }
        conversationService.requireParticipant(conversationId, callerId);
        conversationService.requireParticipant(conversationId, targetUserId);
        if (callerId.equals(targetUserId)) {
            throw new IllegalArgumentException("Cannot call yourself");
        }
        notificationService.sendNotification(targetUserId, Notification.builder()
                .type(signal)
                .conversationId(conversationId)
                .senderId(callerId)
                .receiverId(targetUserId)
                .payload(payload)
                .build());
    }
}
