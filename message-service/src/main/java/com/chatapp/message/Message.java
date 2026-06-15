package com.chatapp.message;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "message")
public class Message extends PanacheEntity {

    @Column(unique = true, nullable = false, updatable = false)
    public String messageUuid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_data_id", nullable = false, updatable = false)
    public MessageData messageData;

    @Column(nullable = false, updatable = false)
    public String chatUuid;

    @Column(nullable = false, updatable = false)
    public String recipient;

    @Column(nullable = false, updatable = false)
    public String recipientDevice;

    @Column(nullable = false, updatable = false)
    public String sender;

    @Column(nullable = false, updatable = false)
    long timestamp;

    @PrePersist
    void assignMessageUuid() {
        if (messageUuid == null || messageUuid.isBlank()) {
            // UUIDv7 (time-ordered) for better performance in the database
            messageUuid = UUID.ofEpochMillis(System.currentTimeMillis())
                    .toString();
        }
    }
}
