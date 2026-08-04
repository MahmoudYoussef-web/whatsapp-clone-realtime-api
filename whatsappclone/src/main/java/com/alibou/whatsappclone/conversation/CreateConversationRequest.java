package com.alibou.whatsappclone.conversation;

import jakarta.validation.constraints.NotBlank;

public record CreateConversationRequest(
        @NotBlank(message = "participantId is required")
        String participantId
) {
}
