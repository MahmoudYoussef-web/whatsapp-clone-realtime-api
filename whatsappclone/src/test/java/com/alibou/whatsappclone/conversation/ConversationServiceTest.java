package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.storage.FileStorageService;
import com.alibou.whatsappclone.user.User;
import com.alibou.whatsappclone.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationParticipantRepository participantRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConversationMapper mapper;

    @Mock
    private NotificationService notificationService;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private ConversationService conversationService;

    @Test
    void createPrivateConversation_reusesExistingConversation() {
        UUID conversationId = UUID.randomUUID();
        Conversation existing = Conversation.builder().id(conversationId).build();
        when(conversationRepository.findBetweenUsers("user-a", "user-b", ConversationType.PRIVATE))
                .thenReturn(Optional.of(existing));

        UUID result = conversationService.createPrivateConversation("user-a", "user-b");

        assertThat(result).isEqualTo(conversationId);
        verify(conversationRepository, never()).save(any());
        verify(participantRepository, never()).save(any());
    }

    @Test
    void createPrivateConversation_createsNewWithTwoParticipantsWhenMissing() {
        User self = User.builder().id("user-a").build();
        User other = User.builder().id("user-b").build();
        Conversation created = Conversation.builder().id(UUID.randomUUID()).type(ConversationType.PRIVATE).build();

        when(conversationRepository.findBetweenUsers("user-a", "user-b", ConversationType.PRIVATE))
                .thenReturn(Optional.empty());
        when(userRepository.findById("user-a")).thenReturn(Optional.of(self));
        when(userRepository.findById("user-b")).thenReturn(Optional.of(other));
        when(conversationRepository.save(any(Conversation.class))).thenReturn(created);

        UUID result = conversationService.createPrivateConversation("user-a", "user-b");

        assertThat(result).isEqualTo(created.getId());
        verify(conversationRepository).save(any(Conversation.class));
        // exactly one participant row per user (2 total)
        verify(participantRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void createPrivateConversation_rejectsSelfConversation() {
        assertThatThrownBy(() -> conversationService.createPrivateConversation("user-a", "user-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yourself");
    }

    @Test
    void createPrivateConversation_throwsWhenParticipantDoesNotExist() {
        when(conversationRepository.findBetweenUsers("user-a", "ghost", ConversationType.PRIVATE))
                .thenReturn(Optional.empty());
        when(userRepository.findById("user-a")).thenReturn(Optional.of(User.builder().id("user-a").build()));
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.createPrivateConversation("user-a", "ghost"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void requireParticipant_throwsWhenNotParticipant() {
        when(participantRepository.findByConversation_IdAndUser_Id(any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.requireParticipant(UUID.randomUUID(), "user-a"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getConversations_returnsConversationsForUser() {
        User self = User.builder().id("user-a").firstName("A").build();
        User other = User.builder().id("user-b").firstName("B").lastName("Bou").build();
        Conversation conversation = Conversation.builder().id(UUID.randomUUID()).type(ConversationType.PRIVATE).build();
        ConversationParticipant participant = ConversationParticipant.builder()
                .conversation(conversation)
                .user(self)
                .unreadCount(2)
                .build();
        conversation.setParticipants(List.of(participant, ConversationParticipant.builder()
                .conversation(conversation).user(other).build()));

        when(participantRepository.findConversationsByUserId("user-a"))
                .thenReturn(List.of(participant));
        when(mapper.toResponse(conversation, participant, "user-a"))
                .thenReturn(ConversationResponse.builder().id(conversation.getId()).unreadCount(2).build());

        List<ConversationResponse> result = conversationService.getConversations("user-a");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUnreadCount()).isEqualTo(2);
    }

    @Test
    void getOtherParticipantIds_excludesTheGivenUser() {
        Conversation conversation = Conversation.builder().id(UUID.randomUUID()).build();
        ConversationParticipant a = ConversationParticipant.builder()
                .conversation(conversation).user(User.builder().id("a").build()).build();
        ConversationParticipant b = ConversationParticipant.builder()
                .conversation(conversation).user(User.builder().id("b").build()).build();
        when(participantRepository.findByConversation_Id(conversation.getId())).thenReturn(List.of(a, b));

        List<String> others = conversationService.getOtherParticipantIds(conversation.getId(), "a");

        assertThat(others).containsExactly("b");
    }

    @Test
    void createGroupConversation_createsGroupWithAdminAndMembers() {
        User creator = User.builder().id("admin").build();
        User m1 = User.builder().id("m1").build();
        Conversation saved = Conversation.builder()
                .id(UUID.randomUUID()).type(ConversationType.GROUP).name("Team").build();
        when(userRepository.findById("admin")).thenReturn(Optional.of(creator));
        when(userRepository.findById("m1")).thenReturn(Optional.of(m1));
        when(conversationRepository.save(any(Conversation.class))).thenReturn(saved);
        when(participantRepository.findByConversation_Id(any())).thenReturn(List.of());

        UUID result = conversationService.createGroupConversation("admin", "Team", List.of("m1"));

        assertThat(result).isEqualTo(saved.getId());
        verify(conversationRepository).save(any(Conversation.class));
        verify(participantRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void createGroupConversation_rejectsBlankNameAndEmptyMembers() {
        assertThatThrownBy(() -> conversationService.createGroupConversation("admin", "  ", List.of("m1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversationService.createGroupConversation("admin", "Team", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addGroupMember_rejectsNonAdmin() {        UUID id = UUID.randomUUID();
        Conversation group = Conversation.builder().id(id).type(ConversationType.GROUP).build();
        when(conversationRepository.findById(id)).thenReturn(Optional.of(group));
        ConversationParticipant admin = ConversationParticipant.builder()
                .conversation(group).user(User.builder().id("admin").build())
                .role(ParticipantRole.MEMBER).build();
        when(participantRepository.findByConversation_IdAndUser_Id(id, "admin"))
                .thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> conversationService.addGroupMember(id, "admin", "newbie"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void setPinned_updatesParticipantFlag() {
        UUID id = UUID.randomUUID();
        ConversationParticipant p = ConversationParticipant.builder()
                .conversation(Conversation.builder().id(id).build())
                .user(User.builder().id("user-a").build()).build();
        when(participantRepository.findByConversation_IdAndUser_Id(id, "user-a"))
                .thenReturn(Optional.of(p));

        conversationService.setPinned(id, "user-a", true);

        verify(participantRepository).setPinned(id, "user-a", true);
    }

    @Test
    void setArchived_updatesParticipantFlag() {
        UUID id = UUID.randomUUID();
        ConversationParticipant p = ConversationParticipant.builder()
                .conversation(Conversation.builder().id(id).build())
                .user(User.builder().id("user-a").build()).build();
        when(participantRepository.findByConversation_IdAndUser_Id(id, "user-a"))
                .thenReturn(Optional.of(p));

        conversationService.setArchived(id, "user-a", true);

        verify(participantRepository).setArchived(id, "user-a", true);
    }

    @Test
    void setPinned_rejectsNonParticipant() {
        UUID id = UUID.randomUUID();
        when(participantRepository.findByConversation_IdAndUser_Id(id, "ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.setPinned(id, "ghost", true))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getGroupMembers_returnsAdminsFirstWithSelfFlag() {
        UUID id = UUID.randomUUID();
        Conversation group = Conversation.builder().id(id).type(ConversationType.GROUP).name("Team").build();
        User admin = User.builder().id("admin").firstName("Ada").lastName("Min").build();
        User bob = User.builder().id("bob").firstName("Bob").lastName("B").build();
        ConversationParticipant pAdmin = ConversationParticipant.builder()
                .conversation(group).user(admin).role(ParticipantRole.ADMIN).build();
        ConversationParticipant pBob = ConversationParticipant.builder()
                .conversation(group).user(bob).role(ParticipantRole.MEMBER).build();
        when(conversationRepository.findById(id)).thenReturn(Optional.of(group));
        when(participantRepository.findByConversation_IdAndUser_Id(id, "bob"))
                .thenReturn(Optional.of(pBob));
        when(participantRepository.findByConversation_Id(id)).thenReturn(List.of(pBob, pAdmin));

        List<GroupMemberResponse> members = conversationService.getGroupMembers(id, "bob");

        assertThat(members).hasSize(2);
        assertThat(members.get(0).getId()).isEqualTo("admin");
        assertThat(members.get(0).getRole()).isEqualTo(ParticipantRole.ADMIN);
        assertThat(members.get(1).isSelf()).isTrue();
        assertThat(members.get(1).getName()).isEqualTo("Bob B");
    }

    @Test
    void getGroupMembers_rejectsPrivateConversation() {
        UUID id = UUID.randomUUID();
        when(conversationRepository.findById(id)).thenReturn(
                Optional.of(Conversation.builder().id(id).type(ConversationType.PRIVATE).build()));

        assertThatThrownBy(() -> conversationService.getGroupMembers(id, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
