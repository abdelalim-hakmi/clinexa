package com.clinexa.care.common;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Traceability (LLD 20 §1, SRS-D-07) and optimistic locking, with <strong>no identifier</strong>.
 * <p>
 * Separating the audit columns from the key is what lets {@code ClinicalRecord} carry them while
 * its primary key is {@code record_id} — one row per patient record, which is also what the
 * composite foreign key {@code (record_id, clinic_id)} needs. {@link BaseEntity} adds the generated
 * key on top for every table that has one of its own.
 * <p>
 * {@code created_by} and {@code updated_by} are filled from {@code CurrentUser} — see
 * {@code CurrentAuditor}, which is also the reason auditing needs no access to the session.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditTrail {

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@CreatedBy
	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@LastModifiedDate
	@Column(name = "updated_at")
	private Instant updatedAt;

	@LastModifiedBy
	@Column(name = "updated_by")
	private UUID updatedBy;

	@Version
	@Column(name = "version", nullable = false)
	private int version;

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public UUID getCreatedBy() {
		return this.createdBy;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public UUID getUpdatedBy() {
		return this.updatedBy;
	}

	public int getVersion() {
		return this.version;
	}

}
