package com.alibou.whatsappclone.reaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddReactionRequest(
        @NotBlank @Size(max = 10) String emoji
) {}
