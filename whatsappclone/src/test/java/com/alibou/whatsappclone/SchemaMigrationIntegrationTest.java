package com.alibou.whatsappclone;

import com.alibou.whatsappclone.conversation.Conversation;
import com.alibou.whatsappclone.conversation.ConversationParticipant;
import com.alibou.whatsappclone.conversation.ConversationParticipantRepository;
import com.alibou.whatsappclone.conversation.ConversationResponse;
import com.alibou.whatsappclone.conversation.ConversationService;
import com.alibou.whatsappclone.message.Message;
import com.alibou.whatsappclone.message.MessageMapper;
import com.alibou.whatsappclone.message.MessagePageResponse;
import com.alibou.whatsappclone.message.MessageResponse;
import com.alibou.whatsappclone.message.MessageService;
import com.alibou.whatsappclone.message.MessageStatus;
import com.alibou.whatsappclone.message.MessageType;
import com.alibou.whatsappclone.message.SendMessageRequest;
import com.alibou.whatsappclone.user.User;
import com.alibou.whatsappclone.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real Spring context against a disposable PostgreSQL container.
 * Proves that: (1) Flyway migrations apply cleanly, (2) the entity mapping
 * matches the migrated schema (ddl-auto=validate), (3) the atomic unread
 * counters and cursor pagination work against a real database.
 * Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SchemaMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    private S3Client s3Client;

    @MockBean
    private S3Presigner s3Presigner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationParticipantRepository participantRepository;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MessageService messageService;

    private User alice;
    private User bob;

    @BeforeEach
    void cleanDatabase() {
        // Each test starts from an empty schema (tests share one Spring context, so
        // data would otherwise leak between tests - e.g. the idempotent conversation
        // lookup would reuse a conversation created by an earlier test).
        jdbcTemplate.update("DELETE FROM attachments");
        jdbcTemplate.update("UPDATE conversation_participants SET last_read_message_id = NULL");
        jdbcTemplate.update("UPDATE conversations SET last_message_id = NULL");
        jdbcTemplate.update("DELETE FROM messages");
        jdbcTemplate.update("DELETE FROM conversation_participants");
        jdbcTemplate.update("DELETE FROM conversations");
        jdbcTemplate.update("DELETE FROM users");
        seedUsers();
    }

    private void seedUsers() {
        alice = userRepository.save(User.builder().id("alice-id").firstName("Alice").lastName("A").email("alice@test.com").build());
        bob = userRepository.save(User.builder().id("bob-id").firstName("Bob").lastName("B").email("bob@test.com").build());
    }

    @Test
    void flywayMigrationsAreApplied() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isEqualTo(10);

        String conversationTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('conversations')::text", String.class);
        String participantTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('conversation_participants')::text", String.class);
        String attachmentTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('attachments')::text", String.class);
        assertThat(conversationTable).isEqualTo("conversations");
        assertThat(participantTable).isEqualTo("conversation_participants");
        assertThat(attachmentTable).isEqualTo("attachments");

        // V2: reply-to, edit and soft-delete columns
        Integer replyColumn = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'messages' AND column_name = 'reply_to_message_id'",
                Integer.class);
        Integer editedColumn = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'messages' AND column_name = 'edited'",
                Integer.class);
        Integer deletedColumn = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'messages' AND column_name = 'deleted_for_everyone'",
                Integer.class);
        assertThat(replyColumn).isEqualTo(1);
        assertThat(editedColumn).isEqualTo(1);
        assertThat(deletedColumn).isEqualTo(1);

        // V3: reactions table
        String reactionsTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('reactions')::text", String.class);
        assertThat(reactionsTable).isEqualTo("reactions");

        // V4: per-user message deletions table
        String deletionsTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('message_deletions')::text", String.class);
        assertThat(deletionsTable).isEqualTo("message_deletions");
    }

    @Test
    void privateConversationCreationIsIdempotent() {
        UUID first = conversationService.createPrivateConversation(alice.getId(), bob.getId());
        UUID second = conversationService.createPrivateConversation(alice.getId(), bob.getId());
        UUID fromOtherSide = conversationService.createPrivateConversation(bob.getId(), alice.getId());

        assertThat(second).isEqualTo(first);
        assertThat(fromOtherSide).isEqualTo(first);

        Integer participantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_participants WHERE conversation_id = ?",
                Integer.class, first);
        assertThat(participantCount).isEqualTo(2);
    }

    @Test
    void unreadCounterIsIncrementedForReceiverOnlyAndResetOnRead() {
        UUID conversationId = conversationService.createPrivateConversation(alice.getId(), bob.getId());

        messageService.sendTextMessage(conversationId, alice.getId(), new SendMessageRequest("hello", MessageType.TEXT));
        messageService.sendTextMessage(conversationId, alice.getId(), new SendMessageRequest("again", MessageType.TEXT));

        Optional<ConversationParticipant> bobParticipant = participantRepository
                .findByConversation_IdAndUser_Id(conversationId, bob.getId());
        Optional<ConversationParticipant> aliceParticipant = participantRepository
                .findByConversation_IdAndUser_Id(conversationId, alice.getId());
        assertThat(bobParticipant.orElseThrow().getUnreadCount()).isEqualTo(2);
        assertThat(aliceParticipant.orElseThrow().getUnreadCount()).isZero();

        messageService.markMessagesAsRead(conversationId, bob.getId());

        assertThat(participantRepository.findByConversation_IdAndUser_Id(conversationId, bob.getId())
                .orElseThrow().getUnreadCount()).isZero();

        // Alice's own messages must NOT be marked READ just because Bob opened the chat.
        Integer readCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND status = 'READ'",
                Integer.class, conversationId);
        assertThat(readCount).isEqualTo(2);
    }

    @Test
    void cursorPaginationReturnsNewestFirstWithHasMore() {
        UUID conversationId = conversationService.createPrivateConversation(alice.getId(), bob.getId());
        for (int i = 1; i <= 5; i++) {
            messageService.sendTextMessage(conversationId, alice.getId(), new SendMessageRequest("msg-" + i, MessageType.TEXT));
        }

        MessagePageResponse firstPage = messageService.getMessages(conversationId, bob.getId(), null, 2);

        assertThat(firstPage.getMessages()).hasSize(2);
        assertThat(firstPage.isHasMore()).isTrue();
        assertThat(firstPage.getMessages().get(0).getContent()).isEqualTo("msg-5");
        assertThat(firstPage.getMessages().get(1).getContent()).isEqualTo("msg-4");
        assertThat(firstPage.getNextCursor()).isEqualTo(firstPage.getMessages().get(1).getId());

        MessagePageResponse secondPage = messageService.getMessages(
                conversationId, bob.getId(), firstPage.getNextCursor(), 2);
        assertThat(secondPage.getMessages()).hasSize(2);
        assertThat(secondPage.getMessages().get(0).getContent()).isEqualTo("msg-3");
        assertThat(secondPage.getMessages().get(1).getContent()).isEqualTo("msg-2");
        assertThat(secondPage.isHasMore()).isTrue();

        MessagePageResponse lastPage = messageService.getMessages(
                conversationId, bob.getId(), secondPage.getNextCursor(), 2);
        assertThat(lastPage.getMessages()).hasSize(1);
        assertThat(lastPage.getMessages().get(0).getContent()).isEqualTo("msg-1");
        assertThat(lastPage.isHasMore()).isFalse();

        // Regression guard: sibling bulk updates (unread counters, statuses) run with
        // clearAutomatically=true and used to silently discard the dirty
        // conversation.lastMessage change, leaving the preview empty.
        ConversationResponse preview = conversationService.getConversations(bob.getId()).get(0);
        assertThat(preview.getLastMessageId()).isEqualTo(5L);
        assertThat(preview.getLastMessage()).isEqualTo("msg-5");
        assertThat(preview.getLastMessageStatus()).isEqualTo(MessageStatus.DELIVERED);
    }

    @Test
    void messageStatusTransitionsAreGuarded() {
        UUID conversationId = conversationService.createPrivateConversation(alice.getId(), bob.getId());
        messageService.sendTextMessage(conversationId, alice.getId(), new SendMessageRequest("ping", MessageType.TEXT));

        Long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, conversationId);

        // Phase 2 delivery model: a sent message stays SENT until the receiver
        // ACKs it over WebSocket (or loads the history).
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE id = ?", String.class, messageId);
        assertThat(status).isEqualTo(MessageStatus.SENT.name());

        // Receiver ACK: SENT -> DELIVERED, a one-shot transition.
        assertThat(messageService.acknowledgeDelivered(messageId, bob.getId())).isTrue();
        status = jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE id = ?", String.class, messageId);
        assertThat(status).isEqualTo(MessageStatus.DELIVERED.name());

        // No double transition.
        assertThat(messageService.acknowledgeDelivered(messageId, bob.getId())).isFalse();
        assertThat(messageService.markDeliveredIfSent(messageId)).isFalse();

        // READ on top of DELIVERED is allowed once...
        messageService.markMessagesAsRead(conversationId, bob.getId());
        status = jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE id = ?", String.class, messageId);
        assertThat(status).isEqualTo(MessageStatus.READ.name());

        // ...and an already READ message is never downgraded back to DELIVERED.
        assertThat(messageService.markDeliveredIfSent(messageId)).isFalse();
        status = jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE id = ?", String.class, messageId);
        assertThat(status).isEqualTo(MessageStatus.READ.name());
    }

    @Test
    void offlineDeliveryTransitionsPendingMessagesWhenHistoryIsLoaded() {
        UUID conversationId = conversationService.createPrivateConversation(alice.getId(), bob.getId());
        messageService.sendTextMessage(conversationId, alice.getId(), new SendMessageRequest("while away", MessageType.TEXT));

        Long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, conversationId);

        // Bob was offline (never fetched, never ACKed) -> still SENT.
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE id = ?", String.class, messageId);
        assertThat(status).isEqualTo(MessageStatus.SENT.name());

        // Bob opens the chat: fetching history delivers the pending message.
        MessagePageResponse page = messageService.getMessages(conversationId, bob.getId(), null, 30);
        assertThat(page.getMessages()).hasSize(1);
        assertThat(page.getMessages().get(0).getStatus()).isEqualTo(MessageStatus.DELIVERED);

        status = jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE id = ?", String.class, messageId);
        assertThat(status).isEqualTo(MessageStatus.DELIVERED.name());
    }

    @Test
    void replyEditAndDeleteAreMaskedPerViewer() {
        UUID conversationId = conversationService.createPrivateConversation(alice.getId(), bob.getId());

        MessageResponse aliceSent = messageService.sendTextMessage(
                conversationId, alice.getId(), new SendMessageRequest("original", MessageType.TEXT));
        Long originalId = aliceSent.getId();

        // Bob replies to Alice's message.
        messageService.sendTextMessage(conversationId, bob.getId(),
                new SendMessageRequest("replying", MessageType.TEXT, originalId));
        Long replyId = jdbcTemplate.queryForObject(
                "SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, conversationId);

        // The reply is visible with its preview, and the original has a reply flag.
        MessagePageResponse asAlice = messageService.getMessages(conversationId, alice.getId(), null, 30);
        assertThat(asAlice.getMessages()).hasSize(2);
        MessageResponse reply = asAlice.getMessages().stream()
                .filter(m -> m.getId().equals(replyId)).findFirst().orElseThrow();
        assertThat(reply.getReplyToMessageId()).isEqualTo(originalId);
        assertThat(reply.getReplyToContent()).isEqualTo("original");

        // Alice edits her message -> everyone sees the new content and edited=true.
        MessageResponse edited = messageService.editMessage(
                conversationId, originalId, alice.getId(), "edited!");
        assertThat(edited.isEdited()).isTrue();
        assertThat(edited.getContent()).isEqualTo("edited!");

        MessagePageResponse asBob = messageService.getMessages(conversationId, bob.getId(), null, 30);
        MessageResponse editedFromBobView = asBob.getMessages().stream()
                .filter(m -> m.getId().equals(originalId)).findFirst().orElseThrow();
        assertThat(editedFromBobView.getContent()).isEqualTo("edited!");
        assertThat(editedFromBobView.isEdited()).isTrue();

        // Delete for everyone: masked for both sides, attachments dropped, the
        // reply preview is masked too.
        messageService.deleteMessage(conversationId, originalId, alice.getId(), "everyone");
        MessagePageResponse afterDelete = messageService.getMessages(conversationId, bob.getId(), null, 30);
        MessageResponse deletedFromBobView = afterDelete.getMessages().stream()
                .filter(m -> m.getId().equals(originalId)).findFirst().orElseThrow();
        assertThat(deletedFromBobView.isDeleted()).isTrue();
        assertThat(deletedFromBobView.getContent()).isEqualTo(MessageMapper.DELETED_MESSAGE_PLACEHOLDER);
        assertThat(deletedFromBobView.getAttachments()).isEmpty();

        MessageResponse replyWithMaskedPreview = afterDelete.getMessages().stream()
                .filter(m -> m.getId().equals(replyId)).findFirst().orElseThrow();
        assertThat(replyWithMaskedPreview.getReplyToContent())
                .isEqualTo(MessageMapper.DELETED_MESSAGE_PLACEHOLDER);

        // Delete for me: hidden from the deleter only, still visible to the other side.
        messageService.sendTextMessage(conversationId, bob.getId(), new SendMessageRequest("keep me", MessageType.TEXT));
        Long keepId = jdbcTemplate.queryForObject(
                "SELECT id FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, conversationId);
        messageService.deleteMessage(conversationId, keepId, bob.getId(), "me");

        MessagePageResponse bobView = messageService.getMessages(conversationId, bob.getId(), null, 30);
        MessageResponse fromBobView = bobView.getMessages().stream()
                .filter(m -> m.getId().equals(keepId)).findFirst().orElseThrow();
        assertThat(fromBobView.isDeleted()).isTrue();

        MessagePageResponse aliceView = messageService.getMessages(conversationId, alice.getId(), null, 30);
        MessageResponse fromAliceView = aliceView.getMessages().stream()
                .filter(m -> m.getId().equals(keepId)).findFirst().orElseThrow();
        assertThat(fromAliceView.isDeleted()).isFalse();
        assertThat(fromAliceView.getContent()).isEqualTo("keep me");
    }
}
