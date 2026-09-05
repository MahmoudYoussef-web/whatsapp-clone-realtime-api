package com.alibou.whatsappclone.conversation;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateGroupRequest(
        @NotBlank(message = "Group name is required")
        String name,
        List<String> memberIds
) {
}
