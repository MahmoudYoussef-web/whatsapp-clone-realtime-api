package com.alibou.whatsappclone.message;

import com.alibou.whatsappclone.conversation.ConversationParticipant;
import com.alibou.whatsappclone.conversation.ConversationParticipantRepository;
import com.alibou.whatsappclone.conversation.ConversationService;
import com.alibou.whatsappclone.notification.Notification;
import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.notification.NotificationType;
import com.alibou.whatsappclone.storage.FileStorageService;
import com.alibou.whatsappclone.storage.StoredFile;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final ConversationParticipantRepository participantRepository;
    private final ConversationService conversationService;
    private final MessageMapper mapper;
    private final NotificationService notificationService;
    private final FileStorageService fileStorageService;

    @Transactional
    public MessageResponse sendTextMessage(UUID conversationId, String senderId, SendMessageRequest request) {
        ConversationParticipant participant = conversationService.requireParticipant(conversationId, senderId);

        Message message = Message.builder()
                .conversation(participant.getConversation())
                .sender(participant.getUser())
                .type(request.type())
                .content(request.content())
                .status(MessageStatus.SENT)
                .replyToMessage(resolveReplyTarget(conversationId, request.replyToMessageId()))
                .attachments(List.of())
                .build();
        message = messageRepository.save(message);

        conversationService.markLastMessage(conversationId, message);
        participantRepository.incrementUnreadCount(conversationId, senderId);

        MessageResponse response = mapper.toResponse(message, senderId);
        notifyParticipants(conversationId, senderId, NotificationType.MESSAGE, response);
        return response;
    }

    /**
     * A reply target must exist and live in the same conversation as the reply -
     * otherwise the payload would leak message ids from conversations the sender
     * is not part of.
     */
    private Message resolveReplyTarget(UUID conversationId, Long replyToMessageId) {
        if (replyToMessageId == null) {
            return null;
        }
        Message replyTo = messageRepository.findById(replyToMessageId)
                .orElseThrow(() -> new EntityNotFoundException("Message with id " + replyToMessageId + " not found"));
        if (!replyTo.getConversation().getId().equals(conversationId)) {
            throw new IllegalArgumentException("replyToMessageId does not belong to this conversation");
        }
        return replyTo;
    }

    /**
     * Delivered notification fired over WebSocket: the receiver ACKs each incoming
     * message, which transitions it SENT -> DELIVERED (guarded - never downgrades).
     */
    @Transactional
    public boolean acknowledgeDelivered(Long messageId, String ackerId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new EntityNotFoundException("Message with id " + messageId + " not found"));
        conversationService.requireParticipant(message.getConversation().getId(), ackerId);

        if (markDeliveredIfSent(messageId)) {
            notificationService.sendNotification(message.getSender().getId(), Notification.builder()
                    .type(NotificationType.DELIVERED)
                    .conversationId(message.getConversation().getId())
                    .senderId(message.getSender().getId())
                    .receiverId(ackerId)
                    .messageId(messageId)
                    .build());
            return true;
        }
        return false;
    }

    /**
     * Best-effort DELIVERED transition. Guarded - a message that is already
     * READ/DELIVERED is never downgraded.
     */
    @Transactional
    public boolean markDeliveredIfSent(Long messageId) {
        return messageRepository.updateStatusIfCurrent(messageId, MessageStatus.SENT, MessageStatus.DELIVERED) > 0;
    }

    @Transactional
    public MessageResponse uploadAttachment(UUID conversationId, String senderId, MultipartFile file) {
        ConversationParticipant participant = conversationService.requireParticipant(conversationId, senderId);

        StoredFile stored = fileStorageService.upload(file, senderId, conversationId.toString());

        Message message = Message.builder()
                .conversation(participant.getConversation())
                .sender(participant.getUser())
                .type(typeForMime(stored.mimeType()))
                .content(null)
                .status(MessageStatus.SENT)
                .build();
        message = messageRepository.save(message);

        Attachment attachment = Attachment.builder()
                .message(message)
                .objectKey(stored.objectKey())
                .bucket(stored.bucket())
                .mimeType(stored.mimeType())
                .sizeBytes(stored.sizeBytes())
                .build();
        attachment = attachmentRepository.save(attachment);
        message.setAttachments(List.of(attachment));

        conversationService.markLastMessage(conversationId, message);
        participantRepository.incrementUnreadCount(conversationId, senderId);

        MessageResponse response = mapper.toResponse(message, senderId);
        notifyParticipants(conversationId, senderId, NotificationType.MESSAGE, response);
        return response;
    }

    /**
     * Offline delivery: messages sent to the viewer while they were away transition
     * SENT -> DELIVERED when the history is fetched, and each affected sender is
     * notified. Never fires again once every message has been accounted for.
     */
    @Transactional
    public MessagePageResponse getMessages(UUID conversationId, String viewerId, Long before, int limit) {
        conversationService.requireParticipant(conversationId, viewerId);

        List<String> pendingSenders = messageRepository.findPendingSenders(
                conversationId, viewerId, MessageStatus.SENT);
        if (!pendingSenders.isEmpty()) {
            messageRepository.markDeliveredToViewer(conversationId, viewerId,
                    MessageStatus.SENT, MessageStatus.DELIVERED);
            for (String senderId : pendingSenders) {
                notificationService.sendNotification(senderId, Notification.builder()
                        .type(NotificationType.DELIVERED)
                        .conversationId(conversationId)
                        .senderId(senderId)
                        .receiverId(viewerId)
                        .build());
            }
        }

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<Message> page = messageRepository.findMessagesPage(
                conversationId, before, PageRequest.of(0, safeLimit + 1));

        boolean hasMore = page.size() > safeLimit;
        List<Message> slice = hasMore ? page.subList(0, safeLimit) : page;

        Map<Long, List<Attachment>> attachmentsByMessageId = attachmentRepository
                .findByMessage_IdIn(slice.stream().map(Message::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(a -> a.getMessage().getId()));

        Long nextCursor = slice.isEmpty() ? null : slice.get(slice.size() - 1).getId();
        return MessagePageResponse.builder()
                .messages(mapper.toResponses(slice, attachmentsByMessageId, viewerId))
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .build();
    }

    /**
     * Only the sender can edit, and only while the message is not deleted
     * (deleted-for-everyone is final; deleted-for-me hides it from the sender's UI).
     */
    @Transactional
    public MessageResponse editMessage(UUID conversationId, Long messageId, String viewerId, String newContent) {
        conversationService.requireParticipant(conversationId, viewerId);
        Message message = loadMessageInConversation(conversationId, messageId);
        requireSender(message, viewerId);
        if (message.isDeletedForEveryone()) {
            throw new IllegalStateException("Cannot edit a deleted message");
        }

        message.setContent(newContent);
        message.setEdited(true);
        message.setEditedAt(LocalDateTime.now());
        message = messageRepository.save(message);

        MessageResponse response = mapper.toResponse(message, viewerId);
        notifyParticipants(conversationId, viewerId, NotificationType.MESSAGE_EDITED, response);
        return response;
    }

    /**
     * mode=me keeps the message visible to other participants; mode=everyone
     * masks it for everyone (content and attachments are never exposed again).
     */
    @Transactional
    public void deleteMessage(UUID conversationId, Long messageId, String viewerId, String mode) {
        conversationService.requireParticipant(conversationId, viewerId);
        Message message = loadMessageInConversation(conversationId, messageId);
        requireSender(message, viewerId);

        if ("me".equals(mode)) {
            message.setDeletedForSender(true);
        } else {
            message.setDeletedForEveryone(true);
        }
        messageRepository.save(message);

        Notification notification = Notification.builder()
                .type(NotificationType.MESSAGE_DELETED)
                .conversationId(conversationId)
                .senderId(viewerId)
                .receiverId(viewerId)
                .messageId(messageId)
                .build();
        for (String otherId : conversationService.getOtherParticipantIds(conversationId, viewerId)) {
            notificationService.sendNotification(otherId, notification);
        }
    }

    private Message loadMessageInConversation(UUID conversationId, Long messageId) {
        return messageRepository.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Message with id " + messageId + " not found in conversation " + conversationId));
    }

    private void requireSender(Message message, String viewerId) {
        if (!message.getSender().getId().equals(viewerId)) {
            throw new AccessDeniedException("Only the sender can modify this message");
        }
    }

    /**
     * Marks the RECIPIENT's unread messages as READ only (messages the viewer sent
     * themselves are never touched) and resets the viewer's unread counter.
     * Other participants are notified so their UI can update ticks in real time.
     */
    @Transactional
    public void markMessagesAsRead(UUID conversationId, String viewerId) {
        conversationService.requireParticipant(conversationId, viewerId);

        int marked = messageRepository.markMessagesAsRead(conversationId, viewerId, MessageStatus.READ);
        participantRepository.resetUnreadCount(conversationId, viewerId);

        if (marked > 0) {
            for (String otherId : conversationService.getOtherParticipantIds(conversationId, viewerId)) {
                notificationService.sendNotification(otherId, Notification.builder()
                        .type(NotificationType.READ)
                        .conversationId(conversationId)
                        .senderId(viewerId)
                        .receiverId(otherId)
                        .build());
            }
        }
    }

    private void notifyParticipants(UUID conversationId, String senderId, NotificationType type,
                                    MessageResponse message) {
        for (String receiverId : conversationService.getOtherParticipantIds(conversationId, senderId)) {
            notificationService.sendNotification(receiverId, Notification.builder()
                    .type(type)
                    .conversationId(conversationId)
                    .senderId(senderId)
                    .receiverId(receiverId)
                    .message(message)
                    .build());
        }
    }

    private MessageType typeForMime(String mimeType) {
        if (mimeType.startsWith("image/")) {
            return MessageType.IMAGE;
        }
        if (mimeType.startsWith("video/")) {
            return MessageType.VIDEO;
        }
        if (mimeType.startsWith("audio/")) {
            return MessageType.AUDIO;
        }
        return MessageType.FILE;
    }
}
