package com.alibou.whatsappclone.message;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class MessageResponse {

    private final Long id;
    private final String content;
    private final MessageType type;
    private final MessageStatus status;
    private final String senderId;
    private final LocalDateTime createdAt;
    private final List<AttachmentResponse> attachments;
    private final Long replyToMessageId;
    private final String replyToContent;
    private final MessageType replyToType;
    private final boolean edited;
    private final LocalDateTime editedAt;
    private final boolean deleted;
}
