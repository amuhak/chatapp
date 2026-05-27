package com.chatapp.delivery;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

@Entity
@Table(name = "encryption_keys")
public class EncryptionKeys extends PanacheEntity {
    @ManyToOne
    @JoinColumn(name = "device_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    UserDevice deviceToSendTo;

    @Column(unique = true, nullable = false, updatable = false)
    String uuid;

    @Column(nullable = false, updatable = false)
    String keySenderUserUuid;

    @Column(columnDefinition = "TEXT", nullable = false)
    String senderKey;

    // auto assign uuid
    @PrePersist
    void assignUserUuid() {
        if (uuid == null) {
            // UUIDv7 (time-ordered) for better performance in the database
            uuid = UUID.ofEpochMillis(System.currentTimeMillis())
                    .toString();
        }
    }
}
