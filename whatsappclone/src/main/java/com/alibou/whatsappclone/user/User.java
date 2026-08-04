package com.alibou.whatsappclone.user;

import com.alibou.whatsappclone.common.BaseAuditingEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@Setter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseAuditingEntity {

    private static final int LAST_ACTIVE_INTERVAL_MINUTES = 5;

    @Id
    private String id;

    private String firstName;

    private String lastName;

    private String email;

    private LocalDateTime lastSeen;

    @Transient
    public boolean isUserOnline() {
        return lastSeen != null && lastSeen.isAfter(LocalDateTime.now().minusMinutes(LAST_ACTIVE_INTERVAL_MINUTES));
    }
}
