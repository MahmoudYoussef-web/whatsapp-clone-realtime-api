package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.message.MessageStatus;
import com.alibou.whatsappclone.message.MessageType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class ConversationResponse {

    private final UUID id;
    private final ConversationType type;
    private final String name;
    private final int unreadCount;
    private final Long lastMessageId;
    private final String lastMessage;
    private final MessageType lastMessageType;
    private final MessageStatus lastMessageStatus;
    private final String lastMessageSenderId;
    private final LocalDateTime lastMessageTime;
    private final String otherUserId;
    private final boolean otherUserOnline;
    private final LocalDateTime otherUserLastSeen;
    private final String otherUserAvatarUrl;
    private final String groupAvatarUrl;
    private final int memberCount;
    private final boolean pinned;
    private final boolean archived;
    private final Long lastReadMessageId;
}
