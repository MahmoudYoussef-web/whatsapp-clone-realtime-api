package com.alibou.whatsappclone.status;

import com.alibou.whatsappclone.storage.FileStorageService;
import com.alibou.whatsappclone.user.User;
import com.alibou.whatsappclone.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatusServiceTest {

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private StatusService statusService;

    private final User alice = User.builder().id("alice").firstName("Alice").lastName("A").build();

    @Test
    void postText_saves24hTextStatus() {
        when(userRepository.findById("alice")).thenReturn(Optional.of(alice));
        when(statusRepository.save(any(StatusUpdate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StatusResponse response = statusService.postText("alice", "hello");

        assertThat(response.getType()).isEqualTo(StatusType.TEXT);
        assertThat(response.getContent()).isEqualTo("hello");
        assertThat(response.getExpiresAt())
                .isAfter(LocalDateTime.now().plusHours(23));
    }

    @Test
    void postText_rejectsBlankContent() {
        assertThatThrownBy(() -> statusService.postText("alice", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteStatus_rejectsNonOwner() {
        StatusUpdate status = StatusUpdate.builder().id(1L).user(alice)
                .type(StatusType.TEXT).content("x")
                .expiresAt(LocalDateTime.now().plusHours(1)).build();
        when(statusRepository.findById(1L)).thenReturn(Optional.of(status));

        assertThatThrownBy(() -> statusService.deleteStatus("bob", 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getFeed_groupsByUser() {
        StatusUpdate s1 = StatusUpdate.builder().id(1L).user(alice)
                .type(StatusType.TEXT).content("a")
                .expiresAt(LocalDateTime.now().plusHours(1)).build();
        when(userRepository.findById("alice")).thenReturn(Optional.of(alice));
        when(statusRepository.findByExpiresAtAfterOrderByCreatedDateDesc(any()))
                .thenReturn(List.of(s1));

        Map<String, List<StatusResponse>> feed = statusService.getFeed("alice");

        assertThat(feed).containsKey("alice");
        assertThat(feed.get("alice")).hasSize(1);
    }
}
