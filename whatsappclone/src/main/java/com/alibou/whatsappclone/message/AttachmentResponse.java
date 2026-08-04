package com.alibou.whatsappclone.message;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AttachmentResponse {

    private final Long id;
    private final String objectKey;
    private final String mimeType;
    private final long sizeBytes;
    private final Integer width;
    private final Integer height;
    private final Integer durationSeconds;
    private final String url;
}
