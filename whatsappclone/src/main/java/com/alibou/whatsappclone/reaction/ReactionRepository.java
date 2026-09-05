package com.alibou.whatsappclone.reaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReactionRepository extends JpaRepository<Reaction, Long> {

    List<Reaction> findByMessage_Id(Long messageId);

    @Query("""
            SELECT r FROM Reaction r
            WHERE r.message.id IN :messageIds
            """)
    List<Reaction> findByMessageIds(@Param("messageIds") List<Long> messageIds);

    Optional<Reaction> findByMessage_IdAndUser_Id(Long messageId, String userId);

    void deleteByMessage_IdAndUser_Id(Long messageId, String userId);
}
