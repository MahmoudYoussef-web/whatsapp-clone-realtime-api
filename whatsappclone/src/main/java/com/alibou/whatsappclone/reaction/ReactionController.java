package com.alibou.whatsappclone.reaction;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
@Tag(name = "Reaction")
public class ReactionController {

    private final ReactionService reactionService;

    @PostMapping("/{conversation-id}/messages/{message-id}/reactions")
    public ResponseEntity<ReactionResponse> addReaction(
            @PathVariable("conversation-id") UUID conversationId,
            @PathVariable("message-id") Long messageId,
            @Valid @RequestBody AddReactionRequest request,
            Authentication authentication) {
        ReactionResponse response = reactionService.addReaction(
                conversationId, messageId, authentication.getName(), request.emoji());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{conversation-id}/messages/{message-id}/reactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeReaction(
            @PathVariable("conversation-id") UUID conversationId,
            @PathVariable("message-id") Long messageId,
            Authentication authentication) {
        reactionService.removeReaction(conversationId, messageId, authentication.getName());
    }
}
