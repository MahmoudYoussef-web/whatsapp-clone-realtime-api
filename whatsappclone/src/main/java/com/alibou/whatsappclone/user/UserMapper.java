package com.alibou.whatsappclone.user;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class UserMapper {

    public User fromTokenAttributes(Map<String, Object> attributes) {
        User user = User.builder()
                .lastSeen(LocalDateTime.now())
                .build();

        Object subject = attributes.get("sub");
        if (subject != null) {
            user.setId(subject.toString());
        }

        Object givenName = attributes.get("given_name");
        if (givenName == null) {
            givenName = attributes.get("nickname");
        }
        if (givenName != null) {
            user.setFirstName(givenName.toString());
        }

        Object familyName = attributes.get("family_name");
        if (familyName != null) {
            user.setLastName(familyName.toString());
        }

        Object email = attributes.get("email");
        if (email != null) {
            user.setEmail(email.toString());
        }

        return user;
    }

    public UserResponse toUserResponse(User user, boolean online) {
        return toUserResponse(user, online, null);
    }

    public UserResponse toUserResponse(User user, boolean online, String avatarUrl) {
        return UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .online(online)
                .lastSeen(user.getLastSeen())
                .avatarUrl(avatarUrl)
                .build();
    }
}
