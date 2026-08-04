package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.common.BaseAuditingEntity;
import com.alibou.whatsappclone.message.Message;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "conversations")
public class Conversation extends BaseAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private ConversationType type = ConversationType.PRIVATE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_message_id")
    private Message lastMessage;

    @OneToMany(mappedBy = "conversation", fetch = FetchType.LAZY)
    private List<ConversationParticipant> participants = new ArrayList<>();

    @OneToMany(mappedBy = "conversation", fetch = FetchType.LAZY)
    private List<Message> messages = new ArrayList<>();
}
