package com.alibou.whatsappclone.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String userEmail);

    @Query("SELECT u FROM User u WHERE u.id <> :publicId")
    List<User> findAllUsersExceptSelf(@Param("publicId") String publicId);

    Optional<User> findById(String id);
}
