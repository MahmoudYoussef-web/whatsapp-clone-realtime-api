package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.message.Message;
import com.alibou.whatsappclone.notification.Notification;
import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.notification.NotificationType;
import com.alibou.whatsappclone.storage.FileStorageService;
import com.alibou.whatsappclone.storage.StoredFile;
import com.alibou.whatsappclone.storage.StorageProperties;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final ConversationMapper mapper;
    private final NotificationService notificationService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    // create or return existing private chat
    @Transactional
    public UUID createPrivateConversation(String authenticatedUserId, String participantId) {
        if (authenticatedUserId.equals(participantId)) {
            throw new IllegalArgumentException("Cannot create a conversation with yourself");
        }

        return conversationRepository.findBetweenUsers(authenticatedUserId, participantId, ConversationType.PRIVATE)
                .map(Conversation::getId)
                .orElseGet(() -> createNewPrivateConversation(authenticatedUserId, participantId));
    }

    private UUID createNewPrivateConversation(String authenticatedUserId, String participantId) {
        User self = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new EntityNotFoundException("User with id " + authenticatedUserId + " not found"));
        User other = userRepository.findById(participantId)
                .orElseThrow(() -> new EntityNotFoundException("User with id " + participantId + " not found"));

        Conversation conversation = Conversation.builder()
                .type(ConversationType.PRIVATE)
                .build();
        conversation = conversationRepository.save(conversation);

        addParticipant(conversation, self);
        addParticipant(conversation, other);

        return conversation.getId();
    }

    // ------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------

    /**
     * Creates a GROUP conversation. Creator becomes ADMIN, every member a row
     * in conversation_participants (existing table, no new join model).
     */
    @Transactional
    public UUID createGroupConversation(String creatorId, String name, List<String> memberIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
        Set<String> unique = new LinkedHashSet<>(memberIds == null ? List.of() : memberIds);
        unique.remove(creatorId);
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("A group needs at least one other member");
        }
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new EntityNotFoundException("User with id " + creatorId + " not found"));

        Conversation conversation = Conversation.builder()
                .type(ConversationType.GROUP)
                .name(name.strip())
                .createdBy(creatorId)
                .build();
        conversation = conversationRepository.save(conversation);

        addParticipantWithRole(conversation, creator, ParticipantRole.ADMIN);
        List<String> addedIds = new ArrayList<>();
        for (String memberId : unique) {
            User member = userRepository.findById(memberId)
                    .orElseThrow(() -> new EntityNotFoundException("User with id " + memberId + " not found"));
            addParticipantWithRole(conversation, member, ParticipantRole.MEMBER);
            addedIds.add(memberId);
        }
        notifyGroupMembers(conversation.getId(), creatorId, addedIds, NotificationType.MEMBER_ADDED);
        return conversation.getId();
    }

    @Transactional
    public void addGroupMember(UUID conversationId, String adminId, String newMemberId) {
        Conversation conversation = requireGroup(conversationId);
        requireParticipant(conversationId, adminId);
        requireAdmin(conversationId, adminId);
        if (participantRepository.findByConversation_IdAndUser_Id(conversationId, newMemberId).isPresent()) {
            throw new IllegalStateException("User is already a member of this group");
        }
        User member = userRepository.findById(newMemberId)
                .orElseThrow(() -> new EntityNotFoundException("User with id " + newMemberId + " not found"));
        addParticipantWithRole(conversation, member, ParticipantRole.MEMBER);
        notifyGroupMembers(conversationId, adminId, List.of(newMemberId), NotificationType.MEMBER_ADDED);
    }

    @Transactional
    public void removeGroupMember(UUID conversationId, String adminId, String memberId) {
        requireGroup(conversationId);
        requireParticipant(conversationId, adminId);
        requireAdmin(conversationId, adminId);
        if (adminId.equals(memberId)) {
            throw new IllegalArgumentException("Admins cannot remove themselves; leave the group instead");
        }
        ConversationParticipant participant = participantRepository
                .findByConversation_IdAndUser_Id(conversationId, memberId)
                .orElseThrow(() -> new EntityNotFoundException("User " + memberId + " is not a member"));
        participantRepository.delete(participant);
        notifyGroupMembers(conversationId, adminId, List.of(memberId), NotificationType.MEMBER_REMOVED);
    }

    @Transactional
    public void leaveGroup(UUID conversationId, String userId) {
        requireGroup(conversationId);
        ConversationParticipant participant = participantRepository
                .findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> new EntityNotFoundException("User " + userId + " is not a member"));
        participantRepository.delete(participant);
        notifyGroupMembers(conversationId, userId, List.of(userId), NotificationType.MEMBER_REMOVED);
    }

    @Transactional
    public void renameGroup(UUID conversationId, String userId, String name) {        Conversation conversation = requireGroup(conversationId);
        requireParticipant(conversationId, userId);
        requireAdmin(conversationId, userId);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
        conversation.setName(name.strip());
        conversationRepository.save(conversation);
        for (String otherId : getOtherParticipantIds(conversationId, userId)) {
            notificationService.sendNotification(otherId, Notification.builder()
                    .type(NotificationType.GROUP_INFO_UPDATED)
                    .conversationId(conversationId)
                    .senderId(userId)
                    .receiverId(otherId)
                    .build());
        }
    }

    @Transactional
    public void setPinned(UUID conversationId, String userId, boolean pinned) {
        requireParticipant(conversationId, userId);
        participantRepository.setPinned(conversationId, userId, pinned);
    }

    @Transactional
    public void setArchived(UUID conversationId, String userId, boolean archived) {
        requireParticipant(conversationId, userId);
        participantRepository.setArchived(conversationId, userId, archived);
    }

    @Transactional
    public void updateGroupAvatar(UUID conversationId, String userId, MultipartFile file) {
        Conversation conversation = requireGroup(conversationId);
        requireParticipant(conversationId, userId);
        requireAdmin(conversationId, userId);
        StoredFile stored = fileStorageService.uploadAvatar(file, "group-" + conversationId);
        String oldKey = conversation.getAvatarObjectKey();
        conversation.setAvatarObjectKey(stored.objectKey());
        conversationRepository.save(conversation);
        if (oldKey != null) {
            try {
                fileStorageService.delete(stored.bucket(), oldKey);
            } catch (RuntimeException e) {
                log.warn("Could not delete old group avatar {}", oldKey, e);
            }
        }
        for (String otherId : getOtherParticipantIds(conversationId, userId)) {
            notificationService.sendNotification(otherId, Notification.builder()
                    .type(NotificationType.GROUP_INFO_UPDATED)
                    .conversationId(conversationId)
                    .senderId(userId)
                    .receiverId(otherId)
                    .build());
        }
    }

    private Conversation requireGroup(UUID conversationId) {
        Conversation conversation = getConversation(conversationId);
        if (conversation.getType() != ConversationType.GROUP) {
            throw new IllegalArgumentException("Conversation " + conversationId + " is not a group");
        }
        return conversation;
    }

    // group members list, admins first
    @Transactional(readOnly = true)
    public List<GroupMemberResponse> getGroupMembers(UUID conversationId, String viewerId) {
        requireGroup(conversationId);
        requireParticipant(conversationId, viewerId);
        return getParticipants(conversationId).stream()
                .sorted(Comparator.comparing((ConversationParticipant p) -> p.getRole() != ParticipantRole.ADMIN)
                        .thenComparing(p -> displayName(p.getUser())))
                .map(p -> {
                    User user = p.getUser();
                    return GroupMemberResponse.builder()
                            .id(user.getId())
                            .name(displayName(user))
                            .avatarUrl(presignedAvatar(user.getAvatarObjectKey()))
                            .role(p.getRole())
                            .self(user.getId().equals(viewerId))
                            .build();
                })
                .toList();
    }

    private static String displayName(User user) {
        String name = ((user.getFirstName() != null ? user.getFirstName() : "")
                + " " + (user.getLastName() != null ? user.getLastName() : "")).strip();
        return name.isEmpty() ? user.getId() : name;
    }

    private String presignedAvatar(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        try {
            return fileStorageService.presignedGetUrl(storageProperties.bucket(), objectKey);
        } catch (RuntimeException e) {
            log.warn("Could not presign avatar {}", objectKey, e);
            return null;
        }
    }

    private void requireAdmin(UUID conversationId, String userId) {
        ConversationParticipant participant = participantRepository
                .findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "User " + userId + " is not a participant of conversation " + conversationId));
        if (participant.getRole() != ParticipantRole.ADMIN) {
            throw new AccessDeniedException("Only group admins can perform this action");
        }
    }

    private void notifyGroupMembers(UUID conversationId, String actorId, List<String> affectedIds,
                                    NotificationType type) {
        for (String otherId : getOtherParticipantIds(conversationId, actorId)) {
            notificationService.sendNotification(otherId, Notification.builder()
                    .type(type)
                    .conversationId(conversationId)
                    .senderId(actorId)
                    .receiverId(otherId)
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> getConversations(String authenticatedUserId) {
        return participantRepository.findConversationsByUserId(authenticatedUserId)
                .stream()
                .map(p -> mapper.toResponse(p.getConversation(), p, authenticatedUserId))
                .toList();
    }

    @Transactional(readOnly = true)
    public Conversation getConversation(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new EntityNotFoundException("Conversation with id " + conversationId + " not found"));
    }

    @Transactional
    public void markLastMessage(UUID conversationId, Message message) {
        conversationRepository.updateLastMessage(conversationId, message.getId());
    }

    @Transactional(readOnly = true)
    public ConversationParticipant requireParticipant(UUID conversationId, String userId) {
        return participantRepository.findByConversation_IdAndUser_Id(conversationId, userId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "User " + userId + " is not a participant of conversation " + conversationId));
    }

    @Transactional(readOnly = true)
    public List<ConversationParticipant> getParticipants(UUID conversationId) {
        return participantRepository.findByConversation_Id(conversationId);
    }

    @Transactional(readOnly = true)
    public List<String> getOtherParticipantIds(UUID conversationId, String exceptUserId) {
        return getParticipants(conversationId).stream()
                .map(ConversationParticipant::getUser)
                .map(User::getId)
                .filter(id -> !id.equals(exceptUserId))
                .toList();
    }

    private void addParticipant(Conversation conversation, User user) {
        addParticipantWithRole(conversation, user, ParticipantRole.MEMBER);
    }

    private void addParticipantWithRole(Conversation conversation, User user, ParticipantRole role) {
        ConversationParticipant participant = ConversationParticipant.builder()
                .conversation(conversation)
                .user(user)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .unreadCount(0)
                .build();
        participantRepository.save(participant);
    }
}
