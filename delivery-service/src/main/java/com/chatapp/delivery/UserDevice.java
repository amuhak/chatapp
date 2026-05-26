package com.chatapp.delivery;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "user_devices")
public class UserDevice extends PanacheEntity {

    @JoinColumn(nullable = false, updatable = false)
    public String userUUID;

    @Column(unique = true, nullable = false, updatable = false)
    public String deviceId;

    @Column
    public String deviceName;

    // The public key for encryption
    @Column(columnDefinition = "TEXT", nullable = false)
    public String publicIdentityKey;

    // The public key to sign messages, used for verifying signatures
    @Column(columnDefinition = "TEXT", nullable = false)
    public String publicSignKey;

    @PrePersist
    void assignUserUuid() {
        if (deviceId == null || deviceId.isBlank()) {
            // UUIDv7 (time-ordered) for better performance in the database
            deviceId = UUID.ofEpochMillis(System.currentTimeMillis())
                    .toString();
        }
    }
}
