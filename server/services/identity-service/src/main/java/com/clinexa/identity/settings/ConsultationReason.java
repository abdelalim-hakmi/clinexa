package com.clinexa.identity.settings;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import com.clinexa.identity.common.AuditTrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Consultation reason configured by a clinic. Tenant table: {@code @TenantId} filters every
 * SELECT and fills clinic_id on INSERT. Not second-level cached (mutable, per clinic, read by
 * several instances). Archived with {@code active}, never deleted.
 */
@Entity
@Table(name = "consultation_reason")
public class ConsultationReason extends AuditTrail {

	@TenantId
	@Column(name = "clinic_id", nullable = false, updatable = false)
	private UUID clinicId;

	@Column(name = "label", nullable = false)
	private String label;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(name = "indicative_price", precision = 12, scale = 2)
	private BigDecimal indicativePrice;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected ConsultationReason() {
	}

	public ConsultationReason(String label, int durationMinutes) {
		this.label = label;
		this.durationMinutes = durationMinutes;
	}

	public UUID getClinicId() {
		return clinicId;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public int getDurationMinutes() {
		return durationMinutes;
	}

	public void setDurationMinutes(int durationMinutes) {
		this.durationMinutes = durationMinutes;
	}

	public BigDecimal getIndicativePrice() {
		return indicativePrice;
	}

	public void setIndicativePrice(BigDecimal indicativePrice) {
		this.indicativePrice = indicativePrice;
	}

	public boolean isActive() {
		return active;
	}

	public void archive() {
		this.active = false;
	}

}
