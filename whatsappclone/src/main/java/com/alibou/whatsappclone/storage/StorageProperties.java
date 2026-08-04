package com.alibou.whatsappclone.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "application.minio")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        long presignedUrlExpiryMinutes
) {
}
