package com.clinexa.care.record;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import com.clinexa.care.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * The patient <em>of a clinic</em>. There is no global {@code PATIENT} entity at the foundation:
 * a patient of a clinic <strong>is</strong> its record ({@code SEC-04}, "Resolution" of
 * 2026-09-21, LLD 20 §4).
 * <p>
 * The same person seen in two clinics therefore has two records, with nothing linking them in the
 * database. That is not a duplicate to be cleaned up: it is what makes clinic B's record
 * impossible to surface on the A side — not its content and not its existence. The link will only
 * mean something the day a patient portal is decided, and it will be an additive migration then.
 * <p>
 * This table holds identity and contact only. Everything clinical lives in
 * {@code record.clinical.ClinicalRecord}, in another table, so the route separation of I5 has a
 * physical separation underneath it: the administrative use case has <strong>no path at all</strong>
 * to clinical data, not even by a mapping mistake.
 */
@Entity
@Table(name = "patient_record")
public class PatientRecord extends BaseEntity {

	/**
	 * The tenant discriminant. {@code @TenantId} makes Hibernate add it to every {@code SELECT} and
	 * fill it on every {@code INSERT} — the point being that the developer does not have to
	 * remember, which is what keeps the invariant true as the team grows.
	 */
	@TenantId
	@Column(name = "clinic_id", nullable = false, updatable = false)
	private UUID clinicId;

	@Column(name = "last_name", nullable = false)
	private String lastName;

	@Column(name = "first_name", nullable = false)
	private String firstName;

	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	@Column(name = "phone")
	private String phone;

	@Column(name = "coverage")
	private String coverage;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private PatientRecordStatus status = PatientRecordStatus.ACTIVE;

	/** Set when this record has been merged into another (RG-21) — read-only at the foundation. */
	@Column(name = "merged_into")
	private UUID mergedInto;

	protected PatientRecord() {
	}

	public PatientRecord(String lastName, String firstName) {
		this.lastName = lastName;
		this.firstName = firstName;
	}

	public UUID getClinicId() {
		return this.clinicId;
	}

	public String getLastName() {
		return this.lastName;
	}

	public String getFirstName() {
		return this.firstName;
	}

	public LocalDate getDateOfBirth() {
		return this.dateOfBirth;
	}

	public void setDateOfBirth(LocalDate dateOfBirth) {
		this.dateOfBirth = dateOfBirth;
	}

	public String getPhone() {
		return this.phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getCoverage() {
		return this.coverage;
	}

	public void setCoverage(String coverage) {
		this.coverage = coverage;
	}

	public PatientRecordStatus getStatus() {
		return this.status;
	}

	public UUID getMergedInto() {
		return this.mergedInto;
	}

}
