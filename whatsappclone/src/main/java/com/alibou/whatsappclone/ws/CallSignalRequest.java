package com.alibou.whatsappclone.ws;

import com.alibou.whatsappclone.notification.NotificationType;

import java.util.UUID;

public record CallSignalRequest(
        UUID conversationId,
        String targetUserId,
        NotificationType signal,
        String payload
) {
}
