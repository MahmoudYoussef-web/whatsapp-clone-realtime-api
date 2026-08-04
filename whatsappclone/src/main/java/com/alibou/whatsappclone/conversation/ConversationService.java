package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.message.Message;
import com.alibou.whatsappclone.user.User;
import com.alibou.whatsappclone.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final ConversationMapper mapper;

    /**
     * Creates a PRIVATE conversation, or returns the existing one (idempotent).
     * A PRIVATE conversation always holds exactly two participants.
     */
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
        ConversationParticipant participant = ConversationParticipant.builder()
                .conversation(conversation)
                .user(user)
                .role(ParticipantRole.MEMBER)
                .joinedAt(LocalDateTime.now())
                .unreadCount(0)
                .build();
        participantRepository.save(participant);
    }
}
