package com.alibou.whatsappclone.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditMessageRequest(
        @NotBlank(message = "content is required")
        @Size(max = 4000, message = "content must not exceed 4000 characters")
        String content
) {
}
