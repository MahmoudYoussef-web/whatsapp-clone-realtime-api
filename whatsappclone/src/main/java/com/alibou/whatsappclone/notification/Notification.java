package com.alibou.whatsappclone.notification;

import com.alibou.whatsappclone.message.MessageResponse;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * Payload pushed to users over WebSocket (user queue "/user/{id}/chat").
 * Never carries raw media bytes - attachments travel as presigned URLs inside
 * {@link MessageResponse#attachments()}.
 */
@Getter
@Builder
public class Notification {

    private final NotificationType type;
    private final UUID conversationId;
    private final String senderId;
    private final String receiverId;
    private final Long messageId;
    private final MessageResponse message;
    private final boolean typing;
    /** Opaque signaling payload (WebRTC SDP / ICE JSON). Never persisted. */
    private final String payload;
}
