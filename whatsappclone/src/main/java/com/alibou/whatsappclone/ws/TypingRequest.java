package com.alibou.whatsappclone.ws;

import java.util.UUID;

public record TypingRequest(
        UUID conversationId,
        boolean typing
) {
}
