package com.alibou.whatsappclone.reaction;

import com.alibou.whatsappclone.conversation.ConversationService;
import com.alibou.whatsappclone.message.Message;
import com.alibou.whatsappclone.message.MessageMapper;
import com.alibou.whatsappclone.message.MessageRepository;
import com.alibou.whatsappclone.message.MessageResponse;
import com.alibou.whatsappclone.notification.Notification;
import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.notification.NotificationType;
import com.alibou.whatsappclone.user.User;
import com.alibou.whatsappclone.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReactionService {

    private final ReactionRepository reactionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ConversationService conversationService;
    private final NotificationService notificationService;
    private final MessageMapper messageMapper;

    @Transactional
    public ReactionResponse addReaction(UUID conversationId, Long messageId, String userId, String emoji) {
        conversationService.requireParticipant(conversationId, userId);
        Message message = loadMessage(conversationId, messageId);

        Reaction existing = reactionRepository.findByMessage_IdAndUser_Id(messageId, userId).orElse(null);

        if (existing != null) {
            if (existing.getEmoji().equals(emoji)) {
                return toResponse(existing);
            }
            existing.setEmoji(emoji);
            existing = reactionRepository.save(existing);
            notifyReactionChange(conversationId, message, userId, NotificationType.REACTION_UPDATED);
            return toResponse(existing);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        Reaction reaction = Reaction.builder()
                .message(message)
                .user(user)
                .emoji(emoji)
                .build();
        reaction = reactionRepository.save(reaction);

        notifyReactionChange(conversationId, message, userId, NotificationType.REACTION_ADDED);
        return toResponse(reaction);
    }

    @Transactional
    public void removeReaction(UUID conversationId, Long messageId, String userId) {
        conversationService.requireParticipant(conversationId, userId);
        Message message = loadMessage(conversationId, messageId);

        reactionRepository.deleteByMessage_IdAndUser_Id(messageId, userId);
        notifyReactionChange(conversationId, message, userId, NotificationType.REACTION_REMOVED);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<ReactionResponse>> getReactionsForMessages(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return reactionRepository.findByMessageIds(messageIds).stream()
                .collect(Collectors.groupingBy(
                        r -> r.getMessage().getId(),
                        Collectors.mapping(this::toResponse, Collectors.toList())
                ));
    }

    private Message loadMessage(UUID conversationId, Long messageId) {
        return messageRepository.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new EntityNotFoundException("Message not found in conversation"));
    }

    private ReactionResponse toResponse(Reaction reaction) {
        User user = reaction.getUser();
        String name = (user.getFirstName() != null ? user.getFirstName() : "") +
                (user.getLastName() != null ? " " + user.getLastName() : "");
        return ReactionResponse.builder()
                .id(reaction.getId())
                .emoji(reaction.getEmoji())
                .userId(user.getId())
                .userName(name.trim())
                .build();
    }

    private void notifyReactionChange(UUID conversationId, Message message, String actorId, NotificationType type) {
        List<ReactionResponse> reactions = reactionRepository.findByMessage_Id(message.getId()).stream()
                .map(this::toResponse)
                .toList();
        MessageResponse messageResponse = messageMapper.toResponse(message, actorId, reactions);

        for (String receiverId : conversationService.getOtherParticipantIds(conversationId, actorId)) {
            notificationService.sendNotification(receiverId, Notification.builder()
                    .type(type)
                    .conversationId(conversationId)
                    .senderId(actorId)
                    .receiverId(receiverId)
                    .messageId(message.getId())
                    .message(messageResponse)
                    .build());
        }
    }
}
