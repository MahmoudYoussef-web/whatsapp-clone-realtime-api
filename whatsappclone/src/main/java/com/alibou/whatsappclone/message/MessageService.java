package com.alibou.whatsappclone.message;

import com.alibou.whatsappclone.conversation.ConversationParticipant;
import com.alibou.whatsappclone.conversation.ConversationParticipantRepository;
import com.alibou.whatsappclone.conversation.ConversationService;
import com.alibou.whatsappclone.notification.Notification;
import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.notification.NotificationType;
import com.alibou.whatsappclone.reaction.ReactionResponse;
import com.alibou.whatsappclone.reaction.ReactionService;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final MessageDeletionRepository messageDeletionRepository;
    private final ConversationParticipantRepository participantRepository;
    private final ConversationService conversationService;
    private final MessageMapper mapper;
    private final NotificationService notificationService;
    private final FileStorageService fileStorageService;
    private final ReactionService reactionService;
    /**
     * Null in plain unit tests (Mockito @InjectMocks) — all increments are
     * null-guarded so metrics never break business logic.
     */
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private void countSent(String kind) {
        if (meterRegistry != null) {
            meterRegistry.counter("whatsapp.messages.sent", "kind", kind).increment();
        }
    }

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
        countSent("text");

        MessageResponse response = mapper.toResponse(message, senderId, getReactionsForMessage(message.getId()),
                deletedForViewer(senderId, message.getId()));
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
        if (stored.mimeType().startsWith("image/")) {
            try {
                FileStorageService.ThumbnailResult thumb = fileStorageService.buildThumbnail(
                        file.getBytes(), stored.mimeType(), stored.objectKey());
                if (thumb != null) {
                    attachment.setThumbnailObjectKey(thumb.objectKey());
                    attachment.setWidth(thumb.width());
                    attachment.setHeight(thumb.height());
                }
            } catch (java.io.IOException e) {
                org.slf4j.LoggerFactory.getLogger(MessageService.class)
                        .warn("Could not read image bytes for thumbnail", e);
            }
        }
        attachment = attachmentRepository.save(attachment);
        message.setAttachments(List.of(attachment));

        conversationService.markLastMessage(conversationId, message);
        participantRepository.incrementUnreadCount(conversationId, senderId);
        countSent("media");

        MessageResponse response = mapper.toResponse(message, senderId, getReactionsForMessage(message.getId()),
                deletedForViewer(senderId, message.getId()));
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

        List<Long> messageIds = slice.stream().map(Message::getId).toList();
        Map<Long, List<ReactionResponse>> reactionsByMessageId = reactionService.getReactionsForMessages(messageIds);
        if (reactionsByMessageId == null) {
            reactionsByMessageId = Map.of();
        }
        Set<Long> deletedForViewerIds = messageDeletionRepository.findDeletedMessageIds(viewerId, messageIds);
        if (deletedForViewerIds == null) {
            deletedForViewerIds = Set.of();
        }

        Long nextCursor = slice.isEmpty() ? null : slice.get(slice.size() - 1).getId();
        return MessagePageResponse.builder()
                .messages(mapper.toResponses(slice, attachmentsByMessageId, viewerId, reactionsByMessageId,
                        deletedForViewerIds))
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

        MessageResponse response = mapper.toResponse(message, viewerId, getReactionsForMessage(message.getId()),
                deletedForViewer(viewerId, message.getId()));
        notifyParticipants(conversationId, viewerId, NotificationType.MESSAGE_EDITED, response);
        return response;
    }

    /**
     * mode=me: "delete for me" - removes the message from the acting user's own
     * view only (stored per-user in message_deletions). Any participant can do
     * it; other participants are NOT notified and keep seeing the message
     * untouched. Idempotent - a second call is a no-op.
     * <p>
     * mode=everyone: "delete for everyone" - sender-only, masks the message for
     * every participant (content and attachments are never exposed again).
     */
    @Transactional
    public void deleteMessage(UUID conversationId, Long messageId, String viewerId, String mode) {
        ConversationParticipant participant = conversationService.requireParticipant(conversationId, viewerId);
        Message message = loadMessageInConversation(conversationId, messageId);

        if ("everyone".equals(mode)) {
            requireSender(message, viewerId);
            message.setDeletedForEveryone(true);
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
        } else {
            deleteForMe(message, participant, viewerId);
        }
    }

    private void deleteForMe(Message message, ConversationParticipant participant, String viewerId) {
        if (!messageDeletionRepository.existsByMessage_IdAndUser_Id(message.getId(), viewerId)) {
            messageDeletionRepository.save(MessageDeletion.builder()
                    .message(message)
                    .user(participant.getUser())
                    .build());
        }
    }

    /**
     * In-conversation text search (max 20 hits, newest first). Attachments and
     * reactions are batched exactly like history pages (no N+1).
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> searchMessages(UUID conversationId, String viewerId, String query) {
        conversationService.requireParticipant(conversationId, viewerId);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Message> hits = messageRepository.searchMessages(
                conversationId, query.strip(), PageRequest.of(0, 20));
        Map<Long, List<Attachment>> attachmentsByMessageId = attachmentRepository
                .findByMessage_IdIn(hits.stream().map(Message::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(a -> a.getMessage().getId()));
        return mapper.toResponses(hits, attachmentsByMessageId, viewerId);
    }

    /**
     * Forwards a message to another conversation the forwarder belongs to.
     * Creates a new message (new id, SENT) copying content/type and attachment
     * references (same MinIO objects, no re-upload), flagged as forwarded.
     * Deleted-for-everyone content can never be forwarded again.
     */
    @Transactional
    public MessageResponse forwardMessage(UUID sourceConversationId, Long messageId,
                                          UUID targetConversationId, String forwarderId) {
        conversationService.requireParticipant(sourceConversationId, forwarderId);
        ConversationParticipant targetParticipant =
                conversationService.requireParticipant(targetConversationId, forwarderId);
        Message source = loadMessageInConversation(sourceConversationId, messageId);
        if (source.isDeletedForEveryone()) {
            throw new IllegalStateException("Cannot forward a deleted message");
        }

        Message copy = Message.builder()
                .conversation(targetParticipant.getConversation())
                .sender(targetParticipant.getUser())
                .type(source.getType())
                .content(source.getContent())
                .status(MessageStatus.SENT)
                .forwarded(true)
                .build();
        copy = messageRepository.save(copy);

        List<Attachment> sourceAttachments =
                attachmentRepository.findByMessage_IdIn(List.of(source.getId()));
        List<Attachment> copies = new java.util.ArrayList<>();
        for (Attachment a : sourceAttachments) {
            copies.add(attachmentRepository.save(Attachment.builder()
                    .message(copy)
                    .objectKey(a.getObjectKey())
                    .bucket(a.getBucket())
                    .mimeType(a.getMimeType())
                    .sizeBytes(a.getSizeBytes())
                    .build()));
        }
        copy.setAttachments(copies);

        conversationService.markLastMessage(targetConversationId, copy);
        participantRepository.incrementUnreadCount(targetConversationId, forwarderId);
        countSent("forward");

        MessageResponse response = mapper.toResponse(copy, forwarderId);
        notifyParticipants(targetConversationId, forwarderId, NotificationType.MESSAGE, response);
        return response;
    }

    /**
     * Approximate "message info" (read by...). Read state is conversation-wide
     * (last_read_message_id per participant), so a participant counts as having
     * read this message when their last-read id >= this message id.
     */
    @Transactional(readOnly = true)
    public MessageInfoResponse getMessageInfo(UUID conversationId, Long messageId, String viewerId) {
        conversationService.requireParticipant(conversationId, viewerId);
        Message message = loadMessageInConversation(conversationId, messageId);
        List<MessageInfoResponse.ReaderInfo> readers = conversationService
                .getParticipants(conversationId).stream()
                .filter(p -> !p.getUser().getId().equals(message.getSender().getId()))
                .map(p -> MessageInfoResponse.ReaderInfo.builder()
                        .userId(p.getUser().getId())
                        .read(p.getLastReadMessage() != null
                                && p.getLastReadMessage().getId() >= message.getId())
                        .lastSeen(p.getUser().getLastSeen())
                        .build())
                .toList();
        return MessageInfoResponse.builder()
                .messageId(message.getId())
                .senderId(message.getSender().getId())
                .sentAt(message.getCreatedDate())
                .readers(readers)
                .build();
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

    private List<ReactionResponse> getReactionsForMessage(Long messageId) {
        Map<Long, List<ReactionResponse>> byMessage = reactionService.getReactionsForMessages(List.of(messageId));
        return byMessage == null ? List.of() : byMessage.getOrDefault(messageId, List.of());
    }

    private Set<Long> deletedForViewer(String viewerId, Long messageId) {
        return messageDeletionRepository.existsByMessage_IdAndUser_Id(messageId, viewerId)
                ? Set.of(messageId)
                : Set.of();
    }
}
