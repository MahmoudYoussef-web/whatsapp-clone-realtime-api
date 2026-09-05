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

    /**
     * Profile/group avatar upload: images only, 5 MB cap, stored under
     * avatars/{ownerId}/ so listings never mix with chat media.
     */
    public StoredFile uploadAvatar(MultipartFile file, String ownerId) {
        if (file == null || file.isEmpty()) {
            throw new InvalidMediaException("Uploaded file is empty");
        }
        String mimeType = MediaTypeValidator.mimeTypeFor(file.getOriginalFilename())
                .orElseThrow(() -> new InvalidMediaException(
                        "File type not allowed. Supported: " + MediaTypeValidator.allowedExtensions()));
        if (MediaTypeValidator.categoryFor(mimeType).orElse(null) != MediaTypeValidator.Category.IMAGE) {
            throw new InvalidMediaException("Avatar must be an image");
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            throw new InvalidMediaException("Avatar exceeds the 5MB limit");
        }
        String extension = mimeType.substring(mimeType.indexOf('/') + 1).replace("+", "");
        String objectKey = "avatars/" + ownerId + "/" + UUID.randomUUID() + "." + extension;
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
        return new StoredFile(objectKey, properties.bucket(), mimeType, file.getSize());
    }

        /**
     * Downscales an image to a max 320px preview (ImageIO only, no extra deps).
     * Returns null when the bytes cannot be decoded — callers treat thumbnails
     * as best-effort and must never fail the upload because of them.
     */
    public ThumbnailResult buildThumbnail(byte[] imageBytes, String mimeType, String objectKey) {
        try {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(imageBytes);
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(in);
            if (src == null) {
                return null;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            int max = Math.max(w, h);
            int targetMax = 320;
            java.awt.image.BufferedImage out = src;
            if (max > targetMax) {
                double scale = (double) targetMax / max;
                int tw = Math.max(1, (int) Math.round(w * scale));
                int th = Math.max(1, (int) Math.round(h * scale));
                java.awt.Image scaled = src.getScaledInstance(tw, th, java.awt.Image.SCALE_SMOOTH);
                out = new java.awt.image.BufferedImage(tw, th, java.awt.image.BufferedImage.TYPE_INT_RGB);
                out.getGraphics().drawImage(scaled, 0, 0, null);
            }
            String format = mimeType.contains("png") ? "png" : "jpg";
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, format, bytes);
            String thumbKey = objectKey.replaceFirst("(\\.[a-z0-9]+)$", "_thumb.$1");
            if (thumbKey.equals(objectKey)) {
                thumbKey = objectKey + "_thumb";
            }
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(thumbKey)
                            .contentType(mimeType)
                            .contentLength((long) bytes.size())
                            .build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes.toByteArray()));
            return new ThumbnailResult(thumbKey, w, h);
        } catch (RuntimeException | IOException e) {
            log.warn("Thumbnail generation failed for {}", objectKey, e);
            return null;
        }
    }

    public record ThumbnailResult(String objectKey, int width, int height) {
    }

    public String presignedGetUrl(String bucket, String objectKey) {        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
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
