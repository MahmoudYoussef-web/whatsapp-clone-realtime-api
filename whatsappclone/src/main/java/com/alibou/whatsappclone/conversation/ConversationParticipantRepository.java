package com.alibou.whatsappclone.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationParticipantRepository extends JpaRepository<ConversationParticipant, Long> {

    @Query("""
            SELECT p FROM ConversationParticipant p
            JOIN FETCH p.conversation c
            LEFT JOIN FETCH c.lastMessage lm
            WHERE p.user.id = :userId
            ORDER BY COALESCE(lm.createdDate, c.createdDate) DESC
            """)
    List<ConversationParticipant> findConversationsByUserId(@Param("userId") String userId);

    Optional<ConversationParticipant> findByConversation_IdAndUser_Id(UUID conversationId, String userId);

    List<ConversationParticipant> findByConversation_Id(UUID conversationId);

    /**
     * Atomically increments the unread counter of every participant except the sender.
     * Single UPDATE statement -> race-condition safe (no read-modify-write in Java).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ConversationParticipant p
            SET p.unreadCount = p.unreadCount + 1,
                p.lastModifiedDate = CURRENT_TIMESTAMP
            WHERE p.conversation.id = :conversationId
              AND p.user.id <> :senderId
            """)
    int incrementUnreadCount(@Param("conversationId") UUID conversationId,
                             @Param("senderId") String senderId);

    /**
     * Resets the unread counter of one participant and pins their last-read message
     * to the newest message id in the conversation. Native SQL because it needs a
     * subquery (SELECT MAX(id)) that JPQL modifying queries do not support.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE conversation_participants
            SET unread_count = 0,
                last_read_message_id = (SELECT MAX(id) FROM messages WHERE conversation_id = :conversationId),
                last_modified_date = CURRENT_TIMESTAMP
            WHERE conversation_id = :conversationId
              AND user_id = :userId
            """, nativeQuery = true)
    int resetUnreadCount(@Param("conversationId") UUID conversationId,
                         @Param("userId") String userId);
}
