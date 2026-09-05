package com.alibou.whatsappclone.user;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UserResponse {

    private final String id;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final boolean online;
    private final LocalDateTime lastSeen;
    private final String avatarUrl;
}
