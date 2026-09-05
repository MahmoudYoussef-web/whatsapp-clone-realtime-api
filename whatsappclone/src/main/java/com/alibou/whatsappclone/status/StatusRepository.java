package com.alibou.whatsappclone.status;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StatusRepository extends JpaRepository<StatusUpdate, Long> {

    List<StatusUpdate> findByExpiresAtAfterOrderByCreatedDateDesc(LocalDateTime now);

    List<StatusUpdate> findByExpiresAtLessThanEqual(LocalDateTime now);

    List<StatusUpdate> findByUser_IdAndExpiresAtAfterOrderByCreatedDateDesc(String userId, LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM StatusUpdate s WHERE s.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
