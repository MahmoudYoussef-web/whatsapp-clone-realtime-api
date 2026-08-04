package com.alibou.whatsappclone.message;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MessagePageResponse {

    private final List<MessageResponse> messages;
    private final boolean hasMore;
    private final Long nextCursor;
}
