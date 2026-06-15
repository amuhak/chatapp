package com.chatapp.message;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "message_data")
public class MessageData extends PanacheEntity {

    @Column(unique = true, nullable = false, updatable = false)
    public String messageDataUuid;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String encryptedPayload;

    @PrePersist
    void assignMessageUuid() {
        if (messageDataUuid == null || messageDataUuid.isBlank()) {
            // UUIDv7 (time-ordered) for better performance in the database
            messageDataUuid = UUID.ofEpochMillis(System.currentTimeMillis())
                    .toString();
        }
    }

}
