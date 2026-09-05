package com.alibou.whatsappclone.message;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
@Tag(name = "Message")
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/{conversation-id}/messages")
    public ResponseEntity<MessagePageResponse> getMessages(
            @PathVariable("conversation-id") UUID conversationId,
            @RequestParam(name = "before", required = false) Long before,
            @RequestParam(name = "limit", defaultValue = "30") int limit,
            Authentication authentication) {
        return ResponseEntity.ok(messageService.getMessages(conversationId, authentication.getName(), before, limit));
    }

    @PostMapping("/{conversation-id}/messages")
    public ResponseEntity<MessageResponse> sendMessage(@PathVariable("conversation-id") UUID conversationId,
                                                       @Valid @RequestBody SendMessageRequest request,
                                                       Authentication authentication) {
        MessageResponse response = messageService.sendTextMessage(conversationId, authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{conversation-id}/messages/attachments")
    public ResponseEntity<MessageResponse> uploadAttachment(
            @PathVariable("conversation-id") UUID conversationId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        MessageResponse response = messageService.uploadAttachment(conversationId, authentication.getName(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{conversation-id}/read")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void markMessagesAsRead(@PathVariable("conversation-id") UUID conversationId,
                                   Authentication authentication) {
        messageService.markMessagesAsRead(conversationId, authentication.getName());
    }

    @PatchMapping("/{conversation-id}/messages/{message-id}")
    public ResponseEntity<MessageResponse> editMessage(
            @PathVariable("conversation-id") UUID conversationId,
            @PathVariable("message-id") Long messageId,
            @Valid @RequestBody EditMessageRequest request,
            Authentication authentication) {
        MessageResponse response = messageService.editMessage(
                conversationId, messageId, authentication.getName(), request.content());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{conversation-id}/messages/{message-id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMessage(
            @PathVariable("conversation-id") UUID conversationId,
            @PathVariable("message-id") Long messageId,
            @RequestParam(name = "mode", defaultValue = "everyone") String mode,
            Authentication authentication) {
        messageService.deleteMessage(conversationId, messageId, authentication.getName(), mode);
    }

    @GetMapping("/{conversation-id}/messages/search")
    public ResponseEntity<List<MessageResponse>> searchMessages(
            @PathVariable("conversation-id") UUID conversationId,
            @RequestParam(name = "q") String query,
            Authentication authentication) {
        return ResponseEntity.ok(messageService.searchMessages(
                conversationId, authentication.getName(), query));
    }

    @PostMapping("/{conversation-id}/messages/{message-id}/forward")
    public ResponseEntity<MessageResponse> forwardMessage(
            @PathVariable("conversation-id") UUID conversationId,
            @PathVariable("message-id") Long messageId,
            @Valid @RequestBody ForwardMessageRequest request,
            Authentication authentication) {
        MessageResponse response = messageService.forwardMessage(
                conversationId, messageId, request.targetConversationId(), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{conversation-id}/messages/{message-id}/info")
    public ResponseEntity<MessageInfoResponse> getMessageInfo(
            @PathVariable("conversation-id") UUID conversationId,
            @PathVariable("message-id") Long messageId,
            Authentication authentication) {
        return ResponseEntity.ok(messageService.getMessageInfo(
                conversationId, messageId, authentication.getName()));
    }
}
