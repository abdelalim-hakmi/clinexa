package com.clinexa.identity.account;

import java.util.Locale;

import com.clinexa.identity.common.ActivityStatus;
import com.clinexa.identity.common.AuditTrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Global identity (no clinic_id, no @TenantId). Carries no role: the role belongs to the
 * (account, clinic) link, see {@code Member} (I3). Never second-level cached: a deactivation
 * must be effective on the next request.
 */
@Entity
@Table(name = "account")
public class Account extends AuditTrail {

	// Stored lower-cased: the DB unique index is on lower(email), so lookups can be exact matches.
	@Column(name = "email", nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "last_name", nullable = false)
	private String lastName;

	@Column(name = "first_name", nullable = false)
	private String firstName;

	@Column(name = "phone")
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ActivityStatus status = ActivityStatus.ACTIVE;

	protected Account() {
	}

	public Account(String email, String passwordHash, String lastName, String firstName) {
		setEmail(email);
		this.passwordHash = passwordHash;
		this.lastName = lastName;
		this.firstName = firstName;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email.strip().toLowerCase(Locale.ROOT);
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public ActivityStatus getStatus() {
		return status;
	}

	public void setStatus(ActivityStatus status) {
		this.status = status;
	}

}
