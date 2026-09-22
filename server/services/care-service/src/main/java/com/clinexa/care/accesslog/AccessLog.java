package com.clinexa.care.accesslog;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One line of the access log: who read what, when, in which clinic.
 * <p>
 * <strong>Append-only, enforced by the database</strong> — a trigger refuses {@code UPDATE} and
 * {@code DELETE} (migration {@code 002}). A log the application could rewrite would be evidence of
 * nothing, which is why the rule lives in the engine and not in a habit. This class has no setter,
 * no {@code @Version} and no update path, so the trigger should never fire; it exists for the day
 * something else tries.
 * <p>
 * It carries <strong>no clinical content</strong>. The log answers "who accessed this record", not
 * "what did it say" — copying the content here would double the data to protect, in a table read
 * by more people than the record itself.
 * <p>
 * It is a tenant entity like any other: a clinic reads its own log and no one else's.
 */
@Entity
@Table(name = "access_log")
public class AccessLog {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@TenantId
	@Column(name = "clinic_id", nullable = false, updatable = false)
	private UUID clinicId;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	/** The role the access was made under — in this clinic, at that moment. */
	@Column(name = "role", nullable = false, updatable = false)
	private String role;

	@Column(name = "action", nullable = false, updatable = false)
	private String action;

	@Column(name = "resource", nullable = false, updatable = false)
	private String resource;

	@Column(name = "resource_id", updatable = false)
	private UUID resourceId;

	/** Joins the log line to the distributed trace of the request that produced it. */
	@Column(name = "trace_id", updatable = false)
	private String traceId;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt = Instant.now();

	protected AccessLog() {
	}

	public AccessLog(UUID accountId, String role, String action, String resource, UUID resourceId, String traceId) {
		this.accountId = accountId;
		this.role = role;
		this.action = action;
		this.resource = resource;
		this.resourceId = resourceId;
		this.traceId = traceId;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getClinicId() {
		return this.clinicId;
	}

	public UUID getAccountId() {
		return this.accountId;
	}

	public String getRole() {
		return this.role;
	}

	public String getAction() {
		return this.action;
	}

	public String getResource() {
		return this.resource;
	}

	public UUID getResourceId() {
		return this.resourceId;
	}

	public String getTraceId() {
		return this.traceId;
	}

	public Instant getOccurredAt() {
		return this.occurredAt;
	}

}
