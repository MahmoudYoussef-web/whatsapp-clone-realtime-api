package com.alibou.whatsappclone.reaction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReactionResponse {
    private Long id;
    private String emoji;
    private String userId;
    private String userName;
}
