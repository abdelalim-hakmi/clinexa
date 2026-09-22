package com.clinexa.identity.practitioner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.clinexa.identity.common.ActivityStatus;
import com.clinexa.identity.common.AuditTrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

/**
 * Professional profile of an account. Global like account (no @TenantId): a practitioner may work
 * in several clinics, and the PRACTITIONER role of each one stays on {@code Member}.
 */
@Entity
@Table(name = "practitioner")
public class Practitioner extends AuditTrail {

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Column(name = "license_number", nullable = false)
	private String licenseNumber;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "languages", nullable = false, columnDefinition = "text[]")
	private List<String> languages = new ArrayList<>();

	@Column(name = "years_experience")
	private Integer yearsExperience;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ActivityStatus status = ActivityStatus.ACTIVE;

	// Lazy by default; Specialty is second-level cached, so resolving the ids costs no extra query.
	@ManyToMany
	@JoinTable(name = "practitioner_specialty",
			joinColumns = @JoinColumn(name = "practitioner_id"),
			inverseJoinColumns = @JoinColumn(name = "specialty_id"))
	private Set<Specialty> specialties = new HashSet<>();

	protected Practitioner() {
	}

	public Practitioner(UUID accountId, String licenseNumber) {
		this.accountId = accountId;
		this.licenseNumber = licenseNumber;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public String getLicenseNumber() {
		return licenseNumber;
	}

	public void setLicenseNumber(String licenseNumber) {
		this.licenseNumber = licenseNumber;
	}

	public List<String> getLanguages() {
		return languages;
	}

	public void setLanguages(List<String> languages) {
		this.languages = languages;
	}

	public Integer getYearsExperience() {
		return yearsExperience;
	}

	public void setYearsExperience(Integer yearsExperience) {
		this.yearsExperience = yearsExperience;
	}

	public ActivityStatus getStatus() {
		return status;
	}

	public void setStatus(ActivityStatus status) {
		this.status = status;
	}

	public Set<Specialty> getSpecialties() {
		return specialties;
	}

}
