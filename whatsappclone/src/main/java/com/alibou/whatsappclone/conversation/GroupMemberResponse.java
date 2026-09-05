package com.alibou.whatsappclone.conversation;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GroupMemberResponse {

    private final String id;
    private final String name;
    private final String avatarUrl;
    private final ParticipantRole role;
    private final boolean self;
}