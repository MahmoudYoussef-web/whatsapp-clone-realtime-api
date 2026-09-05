package com.alibou.whatsappclone.ws;

import com.alibou.whatsappclone.conversation.ConversationParticipant;
import com.alibou.whatsappclone.conversation.Conversation;
import com.alibou.whatsappclone.notification.Notification;
import com.alibou.whatsappclone.notification.NotificationService;
import com.alibou.whatsappclone.notification.NotificationType;
import com.alibou.whatsappclone.conversation.ConversationService;
import com.alibou.whatsappclone.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CallServiceTest {

    @Mock
    private ConversationService conversationService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private CallService callService;

    private final UUID conversationId = UUID.randomUUID();

    @Test
    void relaySignal_forwardsOfferToTarget() {
        ConversationParticipant p = ConversationParticipant.builder()
                .conversation(Conversation.builder().id(conversationId).build())
                .user(User.builder().id("caller").build()).build();
        when(conversationService.requireParticipant(conversationId, "caller")).thenReturn(p);
        when(conversationService.requireParticipant(conversationId, "callee")).thenReturn(p);

        callService.relaySignal(conversationId, "caller", "callee",
                NotificationType.CALL_OFFER, "{\"sdp\":\"...\"}");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationService).sendNotification(eq("callee"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.CALL_OFFER);
        assertThat(captor.getValue().getPayload()).contains("sdp");
    }

    @Test
    void relaySignal_rejectsSelfCall() {
        assertThatThrownBy(() -> callService.relaySignal(
                conversationId, "caller", "caller", NotificationType.CALL_OFFER, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(notificationService, org.mockito.Mockito.never()).sendNotification(any(), any());
    }
}
