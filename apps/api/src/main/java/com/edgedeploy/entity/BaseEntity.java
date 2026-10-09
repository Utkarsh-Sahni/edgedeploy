package com.edgedeploy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * Application-assigned UUID identity.
 *
 * <p>Assigning the id at construction lets us reference it before the INSERT (e.g. in an outbox
 * payload) and gives entities a stable equals/hashCode. Implementing {@link Persistable} stops
 * Spring Data from treating every new entity as detached and issuing a SELECT before the INSERT.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BaseEntity other) || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        return getId().equals(other.getId());
    }

    @Override
    public final int hashCode() {
        return getId().hashCode();
    }
}
