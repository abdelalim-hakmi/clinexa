package com.clinexa.identity.common;

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
 * Traceability (LLD 20 §1, SRS-D-07) and optimistic locking shared by every editable table.
 * <p>
 * The {@code *_by} columns are filled from the identity contract — see {@code CurrentAuditor},
 * which reads {@code CurrentUser} rather than the security context, so the rule that a single class
 * knows the authentication mechanism (INV-6) holds for auditing too. Outside a request there is
 * simply no auditor, and the columns stay empty rather than being attributed to whoever happened to
 * be around.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditTrail extends BaseEntity {

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
		return createdAt;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public UUID getUpdatedBy() {
		return updatedBy;
	}

	public int getVersion() {
		return version;
	}

}
