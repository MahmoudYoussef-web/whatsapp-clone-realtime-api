package com.alibou.whatsappclone.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByMessage_Id(Long messageId);

    List<Attachment> findByMessage_IdIn(Collection<Long> messageIds);
}
