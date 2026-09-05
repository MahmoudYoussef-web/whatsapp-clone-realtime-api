package com.alibou.whatsappclone.status;

import com.alibou.whatsappclone.storage.FileStorageService;
import com.alibou.whatsappclone.storage.StoredFile;
import com.alibou.whatsappclone.user.User;
import com.alibou.whatsappclone.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatusService {

    private static final long STATUS_TTL_HOURS = 24;

    private final StatusRepository statusRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

    @Transactional
    public StatusResponse postText(String userId, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Status content is required");
        }
        User user = requireUser(userId);
        StatusUpdate status = StatusUpdate.builder()
                .user(user)
                .type(StatusType.TEXT)
                .content(content.strip())
                .expiresAt(LocalDateTime.now().plusHours(STATUS_TTL_HOURS))
                .build();
        return toResponse(statusRepository.save(status));
    }

    @Transactional
    public StatusResponse postMedia(String userId, MultipartFile file) {
        User user = requireUser(userId);
        // Stored under avatars/status-{userId}/ (image/video validated by category).
        StoredFile stored = fileStorageService.uploadAvatar(file, "status-" + userId);
        StatusType type = stored.mimeType().startsWith("video/")
                ? StatusType.VIDEO : StatusType.IMAGE;
        if (type == StatusType.VIDEO && !"video/mp4".equals(stored.mimeType())
                && !"video/webm".equals(stored.mimeType())) {
            throw new IllegalArgumentException("Status video must be mp4 or webm");
        }
        StatusUpdate status = StatusUpdate.builder()
                .user(user)
                .type(type)
                .objectKey(stored.objectKey())
                .bucket(stored.bucket())
                .mimeType(stored.mimeType())
                .sizeBytes(stored.sizeBytes())
                .expiresAt(LocalDateTime.now().plusHours(STATUS_TTL_HOURS))
                .build();
        return toResponse(statusRepository.save(status));
    }

    /** Feed grouped per user, newest first; expired rows are never returned. */
    @Transactional(readOnly = true)
    public Map<String, List<StatusResponse>> getFeed(String viewerId) {
        requireUser(viewerId);
        return statusRepository.findByExpiresAtAfterOrderByCreatedDateDesc(LocalDateTime.now())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.groupingBy(StatusResponse::getUserId));
    }

    @Transactional
    public void deleteStatus(String userId, Long statusId) {
        StatusUpdate status = statusRepository.findById(statusId)
                .orElseThrow(() -> new EntityNotFoundException("Status " + statusId + " not found"));
        if (!status.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Only the owner can delete a status");
        }
        statusRepository.delete(status);
        if (status.getObjectKey() != null) {
            try {
                fileStorageService.delete(status.getBucket(), status.getObjectKey());
            } catch (RuntimeException e) {
                log.warn("Could not delete status object {}", status.getObjectKey(), e);
            }
        }
    }

    /**
     * Hourly expiry sweep (needs @EnableScheduling). Deletes DB rows; MinIO
     * objects are removed best-effort so a storage outage never fails cleanup.
     */
    @Transactional
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 * * * *")
    public void deleteExpiredStatuses() {
        for (StatusUpdate s : statusRepository.findByExpiresAtLessThanEqual(LocalDateTime.now())) {
            statusRepository.delete(s);
            if (s.getObjectKey() != null) {
                try {
                    fileStorageService.delete(s.getBucket(), s.getObjectKey());
                } catch (RuntimeException e) {
                    log.warn("Could not delete expired status object {}", s.getObjectKey(), e);
                }
            }
        }
    }

    private User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User with id " + userId + " not found"));
    }

    private StatusResponse toResponse(StatusUpdate status) {
        String url = null;
        if (status.getObjectKey() != null) {
            try {
                url = fileStorageService.presignedGetUrl(status.getBucket(), status.getObjectKey());
            } catch (RuntimeException e) {
                log.warn("Could not presign status {}", status.getId(), e);
            }
        }
        User user = status.getUser();
        return StatusResponse.builder()
                .id(status.getId())
                .userId(user.getId())
                .userName(((user.getFirstName() != null ? user.getFirstName() : "")
                        + " " + (user.getLastName() != null ? user.getLastName() : "")).strip())
                .type(status.getType())
                .content(status.getContent())
                .url(url)
                .mimeType(status.getMimeType())
                .expiresAt(status.getExpiresAt())
                .createdAt(status.getCreatedDate())
                .build();
    }
}
