package com.alibou.whatsappclone.message;

import com.alibou.whatsappclone.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageMapper {

    public static final String DELETED_MESSAGE_PLACEHOLDER = "This message was deleted";

    private final FileStorageService fileStorageService;

    /**
     * Maps a message for a specific viewer: deleted-for-everyone messages and
     * deleted-for-me messages are masked for that viewer only.
     */
    public MessageResponse toResponse(Message message, String viewerId) {
        boolean deleted = message.isDeletedForEveryone()
                || (message.isDeletedForSender() && message.getSender().getId().equals(viewerId));

        List<AttachmentResponse> attachments = deleted || message.getAttachments() == null
                ? List.of()
                : message.getAttachments().stream().map(this::toAttachmentResponse).toList();

        Message replyTo = message.getReplyToMessage();
        boolean replyDeleted = replyTo != null && replyTo.isDeletedForEveryone();

        return MessageResponse.builder()
                .id(message.getId())
                .content(deleted ? DELETED_MESSAGE_PLACEHOLDER : message.getContent())
                .type(message.getType())
                .status(message.getStatus())
                .senderId(message.getSender().getId())
                .createdAt(message.getCreatedDate())
                .attachments(attachments)
                .replyToMessageId(replyTo != null ? replyTo.getId() : null)
                .replyToContent(replyTo != null ? (replyDeleted ? DELETED_MESSAGE_PLACEHOLDER : replyTo.getContent()) : null)
                .replyToType(replyTo != null ? replyTo.getType() : null)
                .edited(message.isEdited())
                .editedAt(message.getEditedAt())
                .deleted(deleted)
                .build();
    }

    public AttachmentResponse toAttachmentResponse(Attachment attachment) {
        String url = fileStorageService.presignedGetUrl(attachment.getBucket(), attachment.getObjectKey());
        return AttachmentResponse.builder()
                .id(attachment.getId())
                .objectKey(attachment.getObjectKey())
                .mimeType(attachment.getMimeType())
                .sizeBytes(attachment.getSizeBytes())
                .width(attachment.getWidth())
                .height(attachment.getHeight())
                .durationSeconds(attachment.getDurationSeconds())
                .url(url)
                .build();
    }

    /**
     * Attaches lazily-loaded attachment lists to messages (avoids N+1 queries for a page).
     */
    public List<MessageResponse> toResponses(List<Message> messages,
                                             Map<Long, List<Attachment>> attachmentsByMessageId,
                                             String viewerId) {
        messages.forEach(m -> m.setAttachments(attachmentsByMessageId.getOrDefault(m.getId(), List.of())));
        return messages.stream().map(m -> toResponse(m, viewerId)).toList();
    }
}
