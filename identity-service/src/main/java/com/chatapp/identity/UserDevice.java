package com.chatapp.identity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_devices")
public class UserDevice extends PanacheEntity {

    @Column(unique = true, nullable = false)
    public String userId;

    @Column(nullable = false)
    public String deviceId;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String publicIdentityKey;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String publicSignedPreKey;
}
