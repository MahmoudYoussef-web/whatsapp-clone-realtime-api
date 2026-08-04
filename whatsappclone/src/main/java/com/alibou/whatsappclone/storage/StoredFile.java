package com.alibou.whatsappclone.storage;

public record StoredFile(String objectKey, String bucket, String mimeType, long sizeBytes) {
}
