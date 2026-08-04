package com.alibou.whatsappclone.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Uploads media to MinIO (S3-compatible), validates files server-side, and issues
 * presigned GET URLs so raw bytes never travel through the REST/WS layer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties properties;

    public StoredFile upload(MultipartFile file, String userId, String conversationId) {
        if (file == null || file.isEmpty()) {
            throw new InvalidMediaException("Uploaded file is empty");
        }

        String mimeType = MediaTypeValidator.mimeTypeFor(file.getOriginalFilename())
                .orElseThrow(() -> new InvalidMediaException(
                        "File type not allowed. Supported: " + MediaTypeValidator.allowedExtensions()));

        MediaTypeValidator.Category category = MediaTypeValidator.categoryFor(mimeType)
                .orElseThrow(() -> new InvalidMediaException("Unsupported MIME type " + mimeType));
        long maxBytes = MediaTypeValidator.maxSizeFor(category);
        if (file.getSize() > maxBytes) {
            throw new InvalidMediaException("File of type " + category.name().toLowerCase(Locale.ROOT)
                    + " exceeds the " + (maxBytes / (1024 * 1024)) + "MB limit");
        }

        String objectKey = buildObjectKey(userId, conversationId, mimeType);
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(objectKey)
                            .contentType(mimeType)
                            .contentLength(file.getSize())
                            .build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new StorageFailureException("Failed to read uploaded file", e);
        } catch (S3Exception e) {
            throw new StorageFailureException("Failed to store file in object storage", e);
        }

        log.info("Stored object '{}' in bucket '{}' ({} bytes, {})",
                objectKey, properties.bucket(), file.getSize(), mimeType);
        return new StoredFile(objectKey, properties.bucket(), mimeType, file.getSize());
    }

    public String presignedGetUrl(String bucket, String objectKey) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(properties.presignedUrlExpiryMinutes()))
                .getObjectRequest(b -> b.bucket(bucket).key(objectKey))
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return presigned.url().toString();
    }

    public void delete(String bucket, String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }

    private String buildObjectKey(String userId, String conversationId, String mimeType) {
        String extension = Optional.ofNullable(mimeType)
                .map(m -> m.substring(m.indexOf('/') + 1).replace("+", ""))
                .orElse("bin");
        return "users/" + userId + "/" + conversationId + "/" + UUID.randomUUID() + "." + extension;
    }
}
