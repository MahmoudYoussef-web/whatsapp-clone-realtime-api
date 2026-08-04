package com.alibou.whatsappclone.ws;

import com.alibou.whatsappclone.conversation.ConversationService;
import com.alibou.whatsappclone.notification.Notification;
import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Relays typing state to the other participants of a conversation. The sender's
 * participant membership is enforced - you cannot spoof typing inside a
 * conversation you do not belong to.
 */
@Service
@RequiredArgsConstructor
public class TypingService {

    private final ConversationService conversationService;
    private final NotificationService notificationService;

    public void relayTyping(UUID conversationId, String senderId, boolean typing) {
        conversationService.requireParticipant(conversationId, senderId);

        for (String receiverId : conversationService.getOtherParticipantIds(conversationId, senderId)) {
            notificationService.sendNotification(receiverId, Notification.builder()
                    .type(NotificationType.TYPING)
                    .conversationId(conversationId)
                    .senderId(senderId)
                    .receiverId(receiverId)
                    .typing(typing)
                    .build());
        }
    }
}
