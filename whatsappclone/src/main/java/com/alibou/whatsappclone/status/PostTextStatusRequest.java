package com.alibou.whatsappclone.status;

import jakarta.validation.constraints.NotBlank;

public record PostTextStatusRequest(
        @NotBlank(message = "content is required")
        String content
) {
}
