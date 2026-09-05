package com.alibou.whatsappclone.message;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class MessageInfoResponse {

    private final Long messageId;
    private final String senderId;
    private final LocalDateTime sentAt;
    private final List<ReaderInfo> readers;

    @Getter
    @Builder
    public static class ReaderInfo {
        private final String userId;
        private final boolean read;
        private final LocalDateTime lastSeen;
    }
}
