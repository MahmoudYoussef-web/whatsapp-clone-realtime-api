package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.message.Message;
import com.alibou.whatsappclone.presence.PresenceService;
import com.alibou.whatsappclone.storage.FileStorageService;
import com.alibou.whatsappclone.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationMapper {

    private final PresenceService presenceService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    public ConversationResponse toResponse(Conversation conversation,
                                           ConversationParticipant viewerParticipant,
                                           String viewerId) {
        boolean isGroup = conversation.getType() == ConversationType.GROUP;
        ConversationParticipant other = isGroup ? null : otherParticipant(conversation, viewerId);
        Message lastMessage = conversation.getLastMessage();

        String name;
        boolean otherOnline = false;
        String otherUserId = null;
        java.time.LocalDateTime otherLastSeen = null;
        String otherAvatarUrl = null;
        String groupAvatarUrl = null;
        if (isGroup) {
            name = conversation.getName() != null ? conversation.getName() : "Group";
            groupAvatarUrl = presignedAvatar(conversation.getAvatarObjectKey());
        } else {
            name = other != null
                    ? other.getUser().getFirstName() + " " + other.getUser().getLastName()
                    : "Conversation";
            otherOnline = other != null && presenceService.isOnline(other.getUser().getId());
            otherUserId = other != null ? other.getUser().getId() : null;
            otherLastSeen = other != null ? other.getUser().getLastSeen() : null;
            otherAvatarUrl = other != null ? presignedAvatar(other.getUser().getAvatarObjectKey()) : null;
        }

        String lastMessageContent = null;
        if (lastMessage != null) {
            boolean deleted = lastMessage.isDeletedForEveryone()
                    || (lastMessage.isDeletedForSender() && lastMessage.getSender().getId().equals(viewerId));
            lastMessageContent = deleted
                    ? com.alibou.whatsappclone.message.MessageMapper.DELETED_MESSAGE_PLACEHOLDER
                    : lastMessage.getContent();
        }

        return ConversationResponse.builder()
                .id(conversation.getId())
                .type(conversation.getType())
                .name(name)
                .unreadCount(viewerParticipant.getUnreadCount())
                .lastMessageId(lastMessage != null ? lastMessage.getId() : null)
                .lastMessage(lastMessageContent)
                .lastMessageType(lastMessage != null ? lastMessage.getType() : null)
                .lastMessageStatus(lastMessage != null ? lastMessage.getStatus() : null)
                .lastMessageSenderId(lastMessage != null ? lastMessage.getSender().getId() : null)
                .lastMessageTime(lastMessage != null ? lastMessage.getCreatedDate() : null)
                .otherUserId(otherUserId)
                .otherUserOnline(otherOnline)
                .otherUserLastSeen(otherLastSeen)
                .otherUserAvatarUrl(otherAvatarUrl)
                .groupAvatarUrl(groupAvatarUrl)
                .memberCount(conversation.getParticipants() != null ? conversation.getParticipants().size() : 0)
                .pinned(viewerParticipant.isPinned())
                .archived(viewerParticipant.isArchived())
                .lastReadMessageId(viewerParticipant.getLastReadMessage() != null
                        ? viewerParticipant.getLastReadMessage().getId() : null)
                .build();
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

    private ConversationParticipant otherParticipant(Conversation conversation, String viewerId) {
        if (conversation.getParticipants() == null) {
            return null;
        }
        return conversation.getParticipants().stream()
                .filter(p -> !p.getUser().getId().equals(viewerId))
                .findFirst()
                .orElse(null);
    }
}
