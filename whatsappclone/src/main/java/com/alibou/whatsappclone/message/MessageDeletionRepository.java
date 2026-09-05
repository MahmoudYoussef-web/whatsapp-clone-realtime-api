package com.alibou.whatsappclone.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface MessageDeletionRepository extends JpaRepository<MessageDeletion, Long> {

    boolean existsByMessage_IdAndUser_Id(Long messageId, String userId);

    /**
     * Message ids the given user removed from their own view ("delete for me").
     * Used to mask a page of messages without an N+1 query per row.
     */
    @Query("""
            SELECT md.message.id FROM MessageDeletion md
            WHERE md.user.id = :userId
              AND md.message.id IN :messageIds
            """)
    Set<Long> findDeletedMessageIds(@Param("userId") String userId,
                                    @Param("messageIds") List<Long> messageIds);
}