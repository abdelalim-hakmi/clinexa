package com.clinexa.identity.outbox;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import com.clinexa.identity.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Transactional outbox row (LLD 20 §7): written in the same transaction as the business change.
 * {@code @TenantId} covers the write path, which always runs inside a tenant request. The relay
 * that publishes rows has no tenant and needs its own nominative I9 exemption (native
 * SELECT … FOR UPDATE SKIP LOCKED) — it is not built yet.
 */
@Entity
@Table(name = "outbound_event")
public class OutboundEvent extends BaseEntity {

	@Column(name = "topic", nullable = false, updatable = false)
	private String topic;

	// Partition key = clinic_id
	@Column(name = "partition_key", nullable = false, updatable = false)
	private String partitionKey;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
	private String payload;

	@TenantId
	@Column(name = "clinic_id", nullable = false, updatable = false)
	private UUID clinicId;

	@Column(name = "trace_id", updatable = false)
	private String traceId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "published_at")
	private Instant publishedAt;

	protected OutboundEvent() {
	}

	public OutboundEvent(String topic, String partitionKey, String payload, String traceId) {
		this.topic = topic;
		this.partitionKey = partitionKey;
		this.payload = payload;
		this.traceId = traceId;
	}

	public String getTopic() {
		return topic;
	}

	public String getPartitionKey() {
		return partitionKey;
	}

	public String getPayload() {
		return payload;
	}

	public UUID getClinicId() {
		return clinicId;
	}

	public String getTraceId() {
		return traceId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public void markPublished(Instant when) {
		this.publishedAt = when;
	}

}
