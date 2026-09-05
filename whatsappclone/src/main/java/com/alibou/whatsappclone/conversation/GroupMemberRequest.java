package com.alibou.whatsappclone.conversation;

import jakarta.validation.constraints.NotBlank;

public record GroupMemberRequest(
        @NotBlank(message = "userId is required")
        String userId
) {
}
