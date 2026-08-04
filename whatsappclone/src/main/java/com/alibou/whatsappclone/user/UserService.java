package com.alibou.whatsappclone.user;

import com.alibou.whatsappclone.presence.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PresenceService presenceService;

    public List<UserResponse> findAllUsersExceptSelf(Authentication connectedUser) {
        return userRepository.findAllUsersExceptSelf(connectedUser.getName())
                .stream()
                .map(u -> userMapper.toUserResponse(u, presenceService.isOnline(u.getId())))
                .toList();
    }
}
