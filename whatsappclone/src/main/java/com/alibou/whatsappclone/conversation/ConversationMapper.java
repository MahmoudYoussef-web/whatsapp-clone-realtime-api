package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.message.Message;
import com.alibou.whatsappclone.presence.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationMapper {

    private final PresenceService presenceService;

    public ConversationResponse toResponse(Conversation conversation,
                                           ConversationParticipant viewerParticipant,
                                           String viewerId) {
        ConversationParticipant other = otherParticipant(conversation, viewerId);
        Message lastMessage = conversation.getLastMessage();

        String name = other != null
                ? other.getUser().getFirstName() + " " + other.getUser().getLastName()
                : "Conversation";

        boolean otherOnline = other != null && presenceService.isOnline(other.getUser().getId());

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
                .lastMessageTime(lastMessage != null ? lastMessage.getCreatedDate() : null)
                .otherUserId(other != null ? other.getUser().getId() : null)
                .otherUserOnline(otherOnline)
                .otherUserLastSeen(other != null ? other.getUser().getLastSeen() : null)
                .build();
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
