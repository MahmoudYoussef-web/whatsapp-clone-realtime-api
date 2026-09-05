package com.alibou.whatsappclone.status;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class StatusResponse {

    private final Long id;
    private final String userId;
    private final String userName;
    private final StatusType type;
    private final String content;
    private final String url;
    private final String mimeType;
    private final LocalDateTime expiresAt;
    private final LocalDateTime createdAt;
}
