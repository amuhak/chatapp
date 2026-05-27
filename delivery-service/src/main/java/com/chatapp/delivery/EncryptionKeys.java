package com.chatapp.delivery;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "encryption_keys")
public class EncryptionKeys extends PanacheEntity {
    @ManyToOne
    @JoinColumn(name = "device_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    UserDevice deviceToSendTo;

    String keySenderUserUuid;

    @Column(columnDefinition = "TEXT", nullable = false)
    String senderKey;
}
