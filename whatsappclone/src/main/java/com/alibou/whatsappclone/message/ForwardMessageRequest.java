package com.alibou.whatsappclone.message;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ForwardMessageRequest(
        @NotNull(message = "targetConversationId is required")
        UUID targetConversationId
) {
}
