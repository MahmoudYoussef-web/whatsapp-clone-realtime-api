package com.alibou.whatsappclone.message;

import com.alibou.whatsappclone.reaction.ReactionResponse;
import com.alibou.whatsappclone.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageMapper {

    public static final String DELETED_MESSAGE_PLACEHOLDER = "This message was deleted";

    private final FileStorageService fileStorageService;

    /**
     * Maps a message for a specific viewer. A message is masked for that viewer when
     * it was deleted for everyone, when the viewer "deleted it for me", or (legacy)
     * when the sender deleted it for themselves. Other participants still see it.
     */
    public MessageResponse toResponse(Message message, String viewerId) {
        return toResponse(message, viewerId, List.of(), Set.of());
    }

    public MessageResponse toResponse(Message message, String viewerId, List<ReactionResponse> reactions) {
        return toResponse(message, viewerId, reactions, Set.of());
    }

    public MessageResponse toResponse(Message message, String viewerId, List<ReactionResponse> reactions,
                                      Set<Long> deletedForViewerIds) {
        boolean deleted = isDeletedForViewer(message, viewerId, deletedForViewerIds);

        List<AttachmentResponse> attachments = deleted || message.getAttachments() == null
                ? List.of()
                : message.getAttachments().stream().map(this::toAttachmentResponse).toList();

        Message replyTo = message.getReplyToMessage();
        boolean replyDeletedForViewer = replyTo != null
                && isDeletedForViewer(replyTo, viewerId, deletedForViewerIds);

        return MessageResponse.builder()
                .id(message.getId())
                .content(deleted ? DELETED_MESSAGE_PLACEHOLDER : message.getContent())
                .type(message.getType())
                .status(message.getStatus())
                .senderId(message.getSender().getId())
                .createdAt(message.getCreatedDate())
                .attachments(attachments)
                .replyToMessageId(replyTo != null ? replyTo.getId() : null)
                .replyToContent(replyTo != null
                        ? (replyDeletedForViewer ? DELETED_MESSAGE_PLACEHOLDER : replyTo.getContent())
                        : null)
                .replyToType(replyTo != null ? replyTo.getType() : null)
                .edited(message.isEdited())
                .editedAt(message.getEditedAt())
                .deleted(deleted)
                .forwarded(message.isForwarded())
                .reactions(reactions)
                .build();
    }

    private boolean isDeletedForViewer(Message message, String viewerId, Set<Long> deletedForViewerIds) {
        return message.isDeletedForEveryone()
                || deletedForViewerIds.contains(message.getId())
                || (message.isDeletedForSender() && message.getSender().getId().equals(viewerId));
    }

    public AttachmentResponse toAttachmentResponse(Attachment attachment) {
        String url = fileStorageService.presignedGetUrl(attachment.getBucket(), attachment.getObjectKey());
        String thumbnailUrl = attachment.getThumbnailObjectKey() != null
                ? fileStorageService.presignedGetUrl(attachment.getBucket(), attachment.getThumbnailObjectKey())
                : null;
        return AttachmentResponse.builder()
                .id(attachment.getId())
                .objectKey(attachment.getObjectKey())
                .mimeType(attachment.getMimeType())
                .sizeBytes(attachment.getSizeBytes())
                .width(attachment.getWidth())
                .height(attachment.getHeight())
                .durationSeconds(attachment.getDurationSeconds())
                .url(url)
                .thumbnailUrl(thumbnailUrl)
                .build();
    }

    /**
     * Attaches lazily-loaded attachment lists to messages (avoids N+1 queries for a page).
     */
    public List<MessageResponse> toResponses(List<Message> messages,
                                             Map<Long, List<Attachment>> attachmentsByMessageId,
                                             String viewerId) {
        return toResponses(messages, attachmentsByMessageId, viewerId, Map.of(), Set.of());
    }

    /**
     * Attaches lazily-loaded attachment lists and reactions to messages (avoids N+1 queries for a page).
     */
    public List<MessageResponse> toResponses(List<Message> messages,
                                             Map<Long, List<Attachment>> attachmentsByMessageId,
                                             String viewerId,
                                             Map<Long, List<ReactionResponse>> reactionsByMessageId) {
        return toResponses(messages, attachmentsByMessageId, viewerId, reactionsByMessageId, Set.of());
    }

    /**
     * Full batch mapping: attachments, reactions and the viewer's own deletions.
     */
    public List<MessageResponse> toResponses(List<Message> messages,
                                             Map<Long, List<Attachment>> attachmentsByMessageId,
                                             String viewerId,
                                             Map<Long, List<ReactionResponse>> reactionsByMessageId,
                                             Set<Long> deletedForViewerIds) {
        messages.forEach(m -> m.setAttachments(attachmentsByMessageId.getOrDefault(m.getId(), List.of())));
        return messages.stream()
                .map(m -> toResponse(m, viewerId, reactionsByMessageId.getOrDefault(m.getId(), List.of()),
                        deletedForViewerIds))
                .toList();
    }
}
