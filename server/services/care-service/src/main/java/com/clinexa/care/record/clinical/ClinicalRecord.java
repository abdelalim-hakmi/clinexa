package com.clinexa.care.record.clinical;

import java.util.UUID;

import org.hibernate.annotations.TenantId;

import com.clinexa.care.common.AuditTrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The clinical volet of a patient record — {@code PRACTITIONER} only.
 * <p>
 * It lives in its own table and in its own package, and both separations are load-bearing. The
 * table, because the administrative use case then has no path to this data at all (LLD 20 §4.1).
 * The package, because INV-5 can then check at build time that no administrative DTO references a
 * type from here — a field leak caught at compilation rather than in a response.
 * <p>
 * The day someone adds a "practitioner's remarks" field, which table it goes in is an explicit
 * question rather than a silent default. That is the whole return on the extra table.
 * <p>
 * It does not extend {@code BaseEntity}: its primary key <em>is</em> {@code record_id}, one row per
 * patient record, which is also what the composite foreign key {@code (record_id, clinic_id)} needs.
 */
@Entity
@Table(name = "clinical_record")
public class ClinicalRecord extends AuditTrail {

	@Id
	@Column(name = "record_id", nullable = false, updatable = false)
	private UUID recordId;

	/**
	 * Redundant with the patient record's own {@code clinic_id}, on purpose: it is what lets
	 * {@code @TenantId} filter this table without a join, and what the composite foreign key checks
	 * for consistency — the database refuses a clinical volet of clinic A on a record of B.
	 */
	@TenantId
	@Column(name = "clinic_id", nullable = false, updatable = false)
	private UUID clinicId;

	@Column(name = "medical_history")
	private String medicalHistory;

	@Column(name = "surgical_history")
	private String surgicalHistory;

	@Column(name = "family_history")
	private String familyHistory;

	protected ClinicalRecord() {
	}

	public ClinicalRecord(UUID recordId) {
		this.recordId = recordId;
	}

	public UUID getRecordId() {
		return this.recordId;
	}

	public UUID getClinicId() {
		return this.clinicId;
	}

	public String getMedicalHistory() {
		return this.medicalHistory;
	}

	public void setMedicalHistory(String medicalHistory) {
		this.medicalHistory = medicalHistory;
	}

	public String getSurgicalHistory() {
		return this.surgicalHistory;
	}

	public void setSurgicalHistory(String surgicalHistory) {
		this.surgicalHistory = surgicalHistory;
	}

	public String getFamilyHistory() {
		return this.familyHistory;
	}

	public void setFamilyHistory(String familyHistory) {
		this.familyHistory = familyHistory;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ClinicalRecord other && this.recordId != null && this.recordId.equals(other.recordId);
	}

	@Override
	public int hashCode() {
		return ClinicalRecord.class.hashCode();
	}

}
