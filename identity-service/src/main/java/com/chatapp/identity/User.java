package com.chatapp.identity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "app_user")
public class User extends PanacheEntity {

    @Column(unique = true, nullable = false)
    public String username;

    @Column(name = "user_uuid", unique = true, nullable = false, updatable = false)
    public String userUuid;

    @Column(nullable = false)
    public String passwordHash;

    @PrePersist
    void assignUserUuid() {
        if (userUuid == null || userUuid.isBlank()) {
            userUuid = UUID.randomUUID().toString();
        }
    }
}
