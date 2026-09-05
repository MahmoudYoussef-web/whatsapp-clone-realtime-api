package com.alibou.whatsappclone.user;

import com.alibou.whatsappclone.presence.PresenceService;
import com.alibou.whatsappclone.storage.FileStorageService;
import com.alibou.whatsappclone.storage.StorageProperties;
import com.alibou.whatsappclone.storage.StoredFile;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PresenceService presenceService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    public List<UserResponse> findAllUsersExceptSelf(Authentication connectedUser) {
        return userRepository.findAllUsersExceptSelf(connectedUser.getName())
                .stream()
                .map(u -> userMapper.toUserResponse(u, presenceService.isOnline(u.getId()), avatarUrl(u)))
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User with id " + userId + " not found"));
        return userMapper.toUserResponse(user, presenceService.isOnline(userId), avatarUrl(user));
    }

    @Transactional
    public UserResponse updateAvatar(String userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User with id " + userId + " not found"));
        StoredFile stored = fileStorageService.uploadAvatar(file, userId);
        String oldKey = user.getAvatarObjectKey();
        user.setAvatarObjectKey(stored.objectKey());
        userRepository.save(user);
        if (oldKey != null) {
            try {
                fileStorageService.delete(stored.bucket(), oldKey);
            } catch (RuntimeException e) {
                log.warn("Could not delete old avatar {}", oldKey, e);
            }
        }
        return userMapper.toUserResponse(user, presenceService.isOnline(userId), avatarUrl(user));
    }

    public String avatarUrl(User user) {
        if (user.getAvatarObjectKey() == null) {
            return null;
        }
        try {
            return fileStorageService.presignedGetUrl(storageProperties.bucket(), user.getAvatarObjectKey());
        } catch (RuntimeException e) {
            log.warn("Could not presign avatar for {}", user.getId(), e);
            return null;
        }
    }
}
