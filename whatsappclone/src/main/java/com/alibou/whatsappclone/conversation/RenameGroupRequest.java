package com.alibou.whatsappclone.conversation;

import jakarta.validation.constraints.NotBlank;

public record RenameGroupRequest(
        @NotBlank(message = "Group name is required")
        String name
) {
}
