package com.alibou.whatsappclone.message;

import com.alibou.whatsappclone.conversation.Conversation;
import com.alibou.whatsappclone.conversation.ConversationParticipant;
import com.alibou.whatsappclone.conversation.ConversationParticipantRepository;
import com.alibou.whatsappclone.conversation.ConversationService;
import com.alibou.whatsappclone.notification.Notification;
import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.notification.NotificationType;
import com.alibou.whatsappclone.storage.FileStorageService;
import com.alibou.whatsappclone.storage.StoredFile;
import com.alibou.whatsappclone.user.User;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private AttachmentRepository attachmentRepository;

    @Mock
    private ConversationParticipantRepository participantRepository;

    @Mock
    private ConversationService conversationService;

    @Mock
    private MessageMapper mapper;

    @Mock
    private NotificationService notificationService;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private MessageService messageService;

    private final UUID conversationId = UUID.randomUUID();
    private final User self = User.builder().id("sender").firstName("A").build();
    private final User other = User.builder().id("receiver").firstName("B").build();
    private final Conversation conversation = Conversation.builder().id(conversationId).build();

    private ConversationParticipant participant(String userId) {
        return ConversationParticipant.builder()
                .conversation(conversation)
                .user(userId.equals("sender") ? self : other)
                .build();
    }

    private Message message(Long id, User sender) {
        return Message.builder().id(id).conversation(conversation).sender(sender).type(MessageType.TEXT)
                .content("hi").status(MessageStatus.SENT).attachments(List.of()).build();
    }

    private MessageResponse response(Long id) {
        return MessageResponse.builder().id(id).content("hi").type(MessageType.TEXT)
                .status(MessageStatus.SENT).senderId("sender").build();
    }

    @Test
    void sendTextMessage_savesSentMessage_incrementsUnreadAndNotifiesReceiver() {
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(conversationService.getOtherParticipantIds(conversationId, "sender")).thenReturn(List.of("receiver"));
        Message saved = message(1L, self);
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(mapper.toResponse(saved, "sender")).thenReturn(response(1L));

        messageService.sendTextMessage(conversationId, "sender", new SendMessageRequest("hi", MessageType.TEXT));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(captor.getValue().getType()).isEqualTo(MessageType.TEXT);

        verify(conversationService).markLastMessage(conversationId, saved);
        verify(participantRepository).incrementUnreadCount(conversationId, "sender");

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).sendNotification(eq("receiver"), notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getType()).isEqualTo(NotificationType.MESSAGE);
        assertThat(notificationCaptor.getValue().getConversationId()).isEqualTo(conversationId);
    }

    @Test
    void sendTextMessage_withReplyAttachesReplyTarget() {
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(conversationService.getOtherParticipantIds(conversationId, "sender")).thenReturn(List.of("receiver"));
        Message replyTarget = message(7L, other);
        Message saved = message(8L, self);
        when(messageRepository.findById(7L)).thenReturn(Optional.of(replyTarget));
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(mapper.toResponse(saved, "sender")).thenReturn(response(8L));

        messageService.sendTextMessage(conversationId, "sender",
                new SendMessageRequest("reply", MessageType.TEXT, 7L));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getReplyToMessage().getId()).isEqualTo(7L);
    }

    @Test
    void sendTextMessage_rejectsReplyFromAnotherConversation() {
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        Message foreign = Message.builder().id(9L)
                .conversation(Conversation.builder().id(UUID.randomUUID()).build())
                .sender(other).build();
        when(messageRepository.findById(9L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> messageService.sendTextMessage(conversationId, "sender",
                new SendMessageRequest("reply", MessageType.TEXT, 9L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replyToMessageId");
        verify(messageRepository, never()).save(any());
    }

    @Test
    void uploadAttachment_derivesMessageTypeFromRealMime() {
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(conversationService.getOtherParticipantIds(conversationId, "sender")).thenReturn(List.of("receiver"));
        StoredFile stored = new StoredFile("users/sender/conv/video.mp4", "bucket", "video/mp4", 1024);
        when(fileStorageService.upload(any(), eq("sender"), eq(conversationId.toString()))).thenReturn(stored);
        Message saved = message(1L, self);
        saved.setType(MessageType.VIDEO);
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        Attachment attachment = Attachment.builder().id(1L).message(saved).objectKey(stored.objectKey())
                .bucket(stored.bucket()).mimeType(stored.mimeType()).sizeBytes(1024L).build();
        when(attachmentRepository.save(any(Attachment.class))).thenReturn(attachment);
        when(mapper.toResponse(saved, "sender")).thenReturn(response(1L));

        MockMultipartFile file = new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{1, 2, 3});
        MessageResponse response = messageService.uploadAttachment(conversationId, "sender", file);

        assertThat(response.getId()).isEqualTo(1L);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(MessageType.VIDEO);
        verify(attachmentRepository).save(any(Attachment.class));
        verify(participantRepository).incrementUnreadCount(conversationId, "sender");
    }

    @Test
    void uploadAttachment_mapsDocumentsToFileType() {
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(conversationService.getOtherParticipantIds(conversationId, "sender")).thenReturn(List.of());
        StoredFile stored = new StoredFile("obj", "bucket", "application/pdf", 500);
        when(fileStorageService.upload(any(), eq("sender"), eq(conversationId.toString()))).thenReturn(stored);
        Message saved = message(1L, self);
        saved.setType(MessageType.FILE);
        when(messageRepository.save(any(Message.class))).thenReturn(saved);
        when(attachmentRepository.save(any(Attachment.class))).thenReturn(
                Attachment.builder().id(1L).message(saved).build());
        when(mapper.toResponse(saved, "sender")).thenReturn(response(1L));

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1});
        messageService.uploadAttachment(conversationId, "sender", file);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(MessageType.FILE);
    }

    @Test
    void getMessages_returnsPageWithCursorAndHasMore() {
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(messageRepository.findPendingSenders(conversationId, "sender", MessageStatus.SENT))
                .thenReturn(List.of());
        Message m1 = message(5L, other);
        Message m2 = message(4L, other);
        Message m3 = message(3L, other);
        // limit=2 -> requests 3 rows, returns 2 + hasMore
        when(messageRepository.findMessagesPage(eq(conversationId), eq(null), any()))
                .thenReturn(List.of(m1, m2, m3));
        when(attachmentRepository.findByMessage_IdIn(List.of(5L, 4L))).thenReturn(List.of());
        when(mapper.toResponses(any(), any(), eq("sender"))).thenReturn(List.of(
                response(5L), response(4L)));

        MessagePageResponse page = messageService.getMessages(conversationId, "sender", null, 2);

        assertThat(page.getMessages()).hasSize(2);
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(4L);
    }

    @Test
    void getMessages_returnsEmptyCursorWhenLastPage() {
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(messageRepository.findPendingSenders(conversationId, "sender", MessageStatus.SENT))
                .thenReturn(List.of());
        Message m1 = message(5L, other);
        when(messageRepository.findMessagesPage(eq(conversationId), eq(4L), any()))
                .thenReturn(List.of(m1));
        when(attachmentRepository.findByMessage_IdIn(List.of(5L))).thenReturn(List.of());
        when(mapper.toResponses(any(), any(), eq("sender"))).thenReturn(List.of(response(5L)));

        MessagePageResponse page = messageService.getMessages(conversationId, "sender", 4L, 30);

        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getNextCursor()).isEqualTo(5L);
    }

    @Test
    void getMessages_offlineDeliveryTransitionsPendingMessagesAndNotifiesSenders() {
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(messageRepository.findPendingSenders(conversationId, "sender", MessageStatus.SENT))
                .thenReturn(List.of("receiver"));
        Message m1 = message(5L, other);
        when(messageRepository.findMessagesPage(eq(conversationId), eq(null), any()))
                .thenReturn(List.of(m1));
        when(attachmentRepository.findByMessage_IdIn(List.of(5L))).thenReturn(List.of());
        when(mapper.toResponses(any(), any(), eq("sender"))).thenReturn(List.of(response(5L)));

        messageService.getMessages(conversationId, "sender", null, 30);

        verify(messageRepository).markDeliveredToViewer(conversationId, "sender",
                MessageStatus.SENT, MessageStatus.DELIVERED);
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).sendNotification(eq("receiver"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.DELIVERED);
        assertThat(captor.getValue().getReceiverId()).isEqualTo("sender");
        assertThat(captor.getValue().getMessageId()).isNull();
    }

    @Test
    void acknowledgeDelivered_notifiesSenderAndTransitions() {
        Message sent = message(1L, self);
        when(messageRepository.findById(1L)).thenReturn(Optional.of(sent));
        when(conversationService.requireParticipant(conversationId, "receiver"))
                .thenReturn(participant("receiver"));
        when(messageRepository.updateStatusIfCurrent(1L, MessageStatus.SENT, MessageStatus.DELIVERED))
                .thenReturn(1);

        assertThat(messageService.acknowledgeDelivered(1L, "receiver")).isTrue();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).sendNotification(eq("sender"), captor.capture());
        Notification notification = captor.getValue();
        assertThat(notification.getType()).isEqualTo(NotificationType.DELIVERED);
        assertThat(notification.getReceiverId()).isEqualTo("receiver");
        assertThat(notification.getMessageId()).isEqualTo(1L);
    }

    @Test
    void acknowledgeDelivered_doesNotNotifyWhenAlreadyDelivered() {
        Message sent = message(1L, other);
        when(messageRepository.findById(1L)).thenReturn(Optional.of(sent));
        when(conversationService.requireParticipant(conversationId, "receiver"))
                .thenReturn(participant("receiver"));
        when(messageRepository.updateStatusIfCurrent(1L, MessageStatus.SENT, MessageStatus.DELIVERED))
                .thenReturn(0);

        assertThat(messageService.acknowledgeDelivered(1L, "receiver")).isFalse();
        verify(notificationService, never()).sendNotification(any(), any());
    }

    @Test
    void acknowledgeDelivered_rejectsNonParticipant() {
        Message sent = message(1L, other);
        when(messageRepository.findById(1L)).thenReturn(Optional.of(sent));
        when(conversationService.requireParticipant(conversationId, "intruder"))
                .thenThrow(new EntityNotFoundException("not a participant"));

        assertThatThrownBy(() -> messageService.acknowledgeDelivered(1L, "intruder"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void editMessage_marksEditedAndNotifiesParticipants() {
        Message sent = message(1L, self);
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(conversationService.getOtherParticipantIds(conversationId, "sender")).thenReturn(List.of("receiver"));
        when(messageRepository.findByIdAndConversationId(1L, conversationId)).thenReturn(Optional.of(sent));
        when(messageRepository.save(sent)).thenReturn(sent);
        when(mapper.toResponse(sent, "sender"))
                .thenReturn(MessageResponse.builder().id(1L).content("edited content").build());

        messageService.editMessage(conversationId, 1L, "sender", "edited content");

        assertThat(sent.getContent()).isEqualTo("edited content");
        assertThat(sent.isEdited()).isTrue();
        assertThat(sent.getEditedAt()).isNotNull();
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).sendNotification(eq("receiver"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.MESSAGE_EDITED);
        assertThat(captor.getValue().getMessage().getContent()).isEqualTo("edited content");
    }

    @Test
    void editMessage_rejectsNonSender() {
        Message sent = message(1L, self);
        when(conversationService.requireParticipant(conversationId, "receiver"))
                .thenReturn(participant("receiver"));
        when(messageRepository.findByIdAndConversationId(1L, conversationId)).thenReturn(Optional.of(sent));

        assertThatThrownBy(() -> messageService.editMessage(conversationId, 1L, "receiver", "hijack"))
                .isInstanceOf(AccessDeniedException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void editMessage_rejectsDeletedMessage() {
        Message sent = message(1L, self);
        sent.setDeletedForEveryone(true);
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(messageRepository.findByIdAndConversationId(1L, conversationId)).thenReturn(Optional.of(sent));

        assertThatThrownBy(() -> messageService.editMessage(conversationId, 1L, "sender", "nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deleted");
    }

    @Test
    void deleteMessage_forEveryoneMasksMessageForAll() {
        Message sent = message(1L, self);
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(conversationService.getOtherParticipantIds(conversationId, "sender")).thenReturn(List.of("receiver"));
        when(messageRepository.findByIdAndConversationId(1L, conversationId)).thenReturn(Optional.of(sent));

        messageService.deleteMessage(conversationId, 1L, "sender", "everyone");

        assertThat(sent.isDeletedForEveryone()).isTrue();
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).sendNotification(eq("receiver"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.MESSAGE_DELETED);
        assertThat(captor.getValue().getMessageId()).isEqualTo(1L);
    }

    @Test
    void deleteMessage_forMeHidesFromSenderOnly() {
        Message sent = message(1L, self);
        when(conversationService.requireParticipant(conversationId, "sender")).thenReturn(participant("sender"));
        when(conversationService.getOtherParticipantIds(conversationId, "sender")).thenReturn(List.of("receiver"));
        when(messageRepository.findByIdAndConversationId(1L, conversationId)).thenReturn(Optional.of(sent));

        messageService.deleteMessage(conversationId, 1L, "sender", "me");

        assertThat(sent.isDeletedForSender()).isTrue();
        assertThat(sent.isDeletedForEveryone()).isFalse();
    }

    @Test
    void deleteMessage_rejectsNonSender() {
        Message sent = message(1L, self);
        when(conversationService.requireParticipant(conversationId, "receiver"))
                .thenReturn(participant("receiver"));
        when(messageRepository.findByIdAndConversationId(1L, conversationId)).thenReturn(Optional.of(sent));

        assertThatThrownBy(() -> messageService.deleteMessage(conversationId, 1L, "receiver", "everyone"))
                .isInstanceOf(AccessDeniedException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void markMessagesAsRead_onlyMarksOthersMessagesAndResetsUnread() {
        when(conversationService.requireParticipant(conversationId, "receiver")).thenReturn(participant("receiver"));
        when(conversationService.getOtherParticipantIds(conversationId, "receiver")).thenReturn(List.of("sender"));
        when(messageRepository.markMessagesAsRead(conversationId, "receiver", MessageStatus.READ)).thenReturn(3);

        messageService.markMessagesAsRead(conversationId, "receiver");

        // The repository query itself filters by sender <> viewer - here we verify the
        // READ status is the one applied and the viewer's counter is reset.
        verify(messageRepository).markMessagesAsRead(eq(conversationId), eq("receiver"), eq(MessageStatus.READ));
        verify(participantRepository).resetUnreadCount(conversationId, "receiver");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).sendNotification(eq("sender"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.READ);
    }

    @Test
    void markMessagesAsRead_doesNotNotifyWhenNothingToMark() {
        when(conversationService.requireParticipant(conversationId, "receiver")).thenReturn(participant("receiver"));
        when(messageRepository.markMessagesAsRead(conversationId, "receiver", MessageStatus.READ)).thenReturn(0);

        messageService.markMessagesAsRead(conversationId, "receiver");

        verify(notificationService, never()).sendNotification(any(), any());
    }
}
