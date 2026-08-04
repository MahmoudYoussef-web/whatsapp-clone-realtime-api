package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.common.StringResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversation")
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    public ResponseEntity<StringResponse> createConversation(@Valid @RequestBody CreateConversationRequest request,
                                                             Authentication authentication) {
        UUID conversationId = conversationService.createPrivateConversation(
                authentication.getName(), request.participantId());
        return ResponseEntity.ok(StringResponse.builder().response(conversationId.toString()).build());
    }

    @GetMapping
    public ResponseEntity<List<ConversationResponse>> getConversations(Authentication authentication) {
        return ResponseEntity.ok(conversationService.getConversations(authentication.getName()));
    }
}
