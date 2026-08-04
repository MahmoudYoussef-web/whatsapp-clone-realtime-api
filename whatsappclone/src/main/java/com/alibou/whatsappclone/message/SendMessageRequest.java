package com.alibou.whatsappclone.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "content is required")
        @Size(max = 4000, message = "content must not exceed 4000 characters")
        String content,
        MessageType type,
        Long replyToMessageId
) {

    public SendMessageRequest {
        if (type == null) {
            type = MessageType.TEXT;
        }
    }

    public SendMessageRequest(String content, MessageType type) {
        this(content, type, null);
    }
}
