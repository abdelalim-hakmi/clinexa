package com.clinexa.identity.settings;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import com.clinexa.identity.common.AuditTrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Closure period declared by a clinic (FR-CAB-05): slots are not generated inside it.
 * Tenant table ({@code @TenantId}); cancelled with {@code active}, never deleted.
 */
@Entity
@Table(name = "closure_period")
public class ClosurePeriod extends AuditTrail {

	@TenantId
	@Column(name = "clinic_id", nullable = false, updatable = false)
	private UUID clinicId;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Column(name = "label")
	private String label;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected ClosurePeriod() {
	}

	public ClosurePeriod(LocalDate startDate, LocalDate endDate, String label) {
		this.startDate = startDate;
		this.endDate = endDate;
		this.label = label;
	}

	public UUID getClinicId() {
		return clinicId;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public String getLabel() {
		return label;
	}

	public boolean isActive() {
		return active;
	}

	public void cancel() {
		this.active = false;
	}

}
