package com.alibou.whatsappclone.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /**
     * Finds the existing PRIVATE conversation between two users, if any.
     * Used to keep private-conversation creation idempotent.
     */
    @Query("""
            SELECT DISTINCT c FROM Conversation c
            JOIN c.participants p1
            JOIN c.participants p2
            WHERE c.type = :type
              AND SIZE(c.participants) = 2
              AND p1.user.id = :userA
              AND p2.user.id = :userB
            """)
    Optional<Conversation> findBetweenUsers(@Param("userA") String userA,
                                            @Param("userB") String userB,
                                            @Param("type") ConversationType type);

    /**
     * Pins the conversation preview to the given message.
     * Implemented as an explicit UPDATE instead of entity mutation + save:
     * sibling bulk updates (unread counters, statuses) run with
     * clearAutomatically=true and would silently discard dirty entity changes.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Conversation c
            SET c.lastMessage.id = :messageId
            WHERE c.id = :conversationId
            """)
    int updateLastMessage(@Param("conversationId") UUID conversationId,
                          @Param("messageId") Long messageId);
}
