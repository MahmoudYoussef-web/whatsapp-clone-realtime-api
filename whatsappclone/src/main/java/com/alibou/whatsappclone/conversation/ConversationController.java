package com.alibou.whatsappclone.conversation;

import com.alibou.whatsappclone.common.StringResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    @PostMapping("/groups")
    public ResponseEntity<StringResponse> createGroup(@Valid @RequestBody CreateGroupRequest request,
                                                      Authentication authentication) {
        UUID conversationId = conversationService.createGroupConversation(
                authentication.getName(), request.name(), request.memberIds());
        return ResponseEntity.ok(StringResponse.builder().response(conversationId.toString()).build());
    }

    @PostMapping("/{conversation-id}/members")
    public ResponseEntity<Void> addGroupMember(@PathVariable("conversation-id") UUID conversationId,
                                               @Valid @RequestBody GroupMemberRequest request,
                                               Authentication authentication) {
        conversationService.addGroupMember(conversationId, authentication.getName(), request.userId());
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{conversation-id}/members/{user-id}")
    public ResponseEntity<Void> removeGroupMember(@PathVariable("conversation-id") UUID conversationId,
                                                  @PathVariable("user-id") String userId,
                                                  Authentication authentication) {
        conversationService.removeGroupMember(conversationId, authentication.getName(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversation-id}/leave")
    public ResponseEntity<Void> leaveGroup(@PathVariable("conversation-id") UUID conversationId,
                                           Authentication authentication) {
        conversationService.leaveGroup(conversationId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{conversation-id}/members")
    public ResponseEntity<List<GroupMemberResponse>> getGroupMembers(
            @PathVariable("conversation-id") UUID conversationId,
            Authentication authentication) {
        return ResponseEntity.ok(conversationService.getGroupMembers(
                conversationId, authentication.getName()));
    }

    @PatchMapping("/{conversation-id}/name")
    public ResponseEntity<Void> renameGroup(@PathVariable("conversation-id") UUID conversationId,
                                            @Valid @RequestBody RenameGroupRequest request,
                                            Authentication authentication) {
        conversationService.renameGroup(conversationId, authentication.getName(), request.name());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{conversation-id}/pin")
    public ResponseEntity<Void> setPinned(@PathVariable("conversation-id") UUID conversationId,
                                          @RequestBody PinArchiveRequest request,
                                          Authentication authentication) {
        conversationService.setPinned(conversationId, authentication.getName(), request.value());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{conversation-id}/archive")
    public ResponseEntity<Void> setArchived(@PathVariable("conversation-id") UUID conversationId,
                                            @RequestBody PinArchiveRequest request,
                                            Authentication authentication) {
        conversationService.setArchived(conversationId, authentication.getName(), request.value());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{conversation-id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> updateGroupAvatar(@PathVariable("conversation-id") UUID conversationId,
                                                  @RequestParam("file") MultipartFile file,
                                                  Authentication authentication) {
        conversationService.updateGroupAvatar(conversationId, authentication.getName(), file);
        return ResponseEntity.accepted().build();
    }
}
