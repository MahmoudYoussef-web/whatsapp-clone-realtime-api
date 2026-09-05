package com.alibou.whatsappclone.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Cursor-based pagination: returns messages strictly older than the given cursor
     * (message id), newest first. The service requests limit + 1 rows to detect "hasMore".
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.conversation.id = :conversationId
              AND (:before IS NULL OR m.id < :before)
            ORDER BY m.id DESC
            """)
    List<Message> findMessagesPage(@Param("conversationId") UUID conversationId,
                                   @Param("before") Long before,
                                   Pageable pageable);

    /**
     * Marks every message sent TO the current user (sender != user) as READ.
     * Messages the user sent themselves are untouched - fixes the original
     * "mark everything as seen" bug.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Message m
            SET m.status = :newStatus,
                m.lastModifiedDate = CURRENT_TIMESTAMP
            WHERE m.conversation.id = :conversationId
              AND m.sender.id <> :userId
              AND m.status <> :newStatus
            """)
    int markMessagesAsRead(@Param("conversationId") UUID conversationId,
                           @Param("userId") String userId,
                           @Param("newStatus") MessageStatus newStatus);

    /**
     * Single message status transition, guarded so an already READ/DELIVERED message
     * is never downgraded.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Message m
            SET m.status = :newStatus,
                m.lastModifiedDate = CURRENT_TIMESTAMP
            WHERE m.id = :messageId
              AND m.status = :expectedStatus
            """)
    int updateStatusIfCurrent(@Param("messageId") Long messageId,
                              @Param("expectedStatus") MessageStatus expectedStatus,
                              @Param("newStatus") MessageStatus newStatus);

    /**
     * Offline delivery: the distinct senders of messages that are still SENT to
     * the given viewer. Called BEFORE the bulk update so the query sees the
     * pre-transition state (the update clears the persistence context).
     */
    @Query("""
            SELECT DISTINCT m.sender.id
            FROM Message m
            WHERE m.conversation.id = :conversationId
              AND m.sender.id <> :userId
              AND m.status = :expectedStatus
            """)
    List<String> findPendingSenders(@Param("conversationId") UUID conversationId,
                                    @Param("userId") String userId,
                                    @Param("expectedStatus") MessageStatus expectedStatus);

    /**
     * Bulk SENT -> DELIVERED transition for every message sent TO the viewer,
     * fired when the viewer loads the conversation history (they were offline).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Message m
            SET m.status = :newStatus,
                m.lastModifiedDate = CURRENT_TIMESTAMP
            WHERE m.conversation.id = :conversationId
              AND m.sender.id <> :userId
              AND m.status = :expectedStatus
            """)
    int markDeliveredToViewer(@Param("conversationId") UUID conversationId,
                              @Param("userId") String userId,
                              @Param("expectedStatus") MessageStatus expectedStatus,
                              @Param("newStatus") MessageStatus newStatus);

    @Query("""
            SELECT m FROM Message m
            WHERE m.id = :messageId
              AND m.conversation.id = :conversationId
            """)
    java.util.Optional<Message> findByIdAndConversationId(@Param("messageId") Long messageId,
                                                          @Param("conversationId") UUID conversationId);

    /**
     * In-conversation text search, newest first, capped by Pageable.
     * Deleted-for-everyone rows are included here and masked at mapping time.
     */
    @Query("""
            SELECT m FROM Message m
            WHERE m.conversation.id = :conversationId
              AND m.content IS NOT NULL
              AND LOWER(m.content) LIKE LOWER(CONCAT('%', :query, '%'))
            ORDER BY m.id DESC
            """)
    List<Message> searchMessages(@Param("conversationId") UUID conversationId,
                                 @Param("query") String query,
                                 Pageable pageable);
}
