package com.clinexa.identity.member;

import java.time.LocalDate;
import java.util.UUID;

import com.clinexa.identity.common.AuditTrail;
import com.clinexa.shared.security.identity.ClinicRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Assignment account × clinic: the role lives here, never on the account (I3).
 * <p>
 * The only tenant table WITHOUT {@code @TenantId} (SEC-13, whitelist of INV-2): L1 reads it by
 * {@code account_id} before the tenant exists. Consequence — no access by bare id: only by
 * account_id, by the TenantContext's clinic_id (filtered by hand) and by (id, clinic_id); writes set
 * clinicId explicitly and check it against the TenantContext. Never second-level cached: a
 * revocation must be effective immediately (guide 4.4).
 */
@Entity
@Table(name = "member")
public class Member extends AuditTrail {

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Column(name = "clinic_id", nullable = false, updatable = false)
	private UUID clinicId;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private ClinicRole role;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private MemberStatus status = MemberStatus.ACTIVE;

	protected Member() {
	}

	public Member(UUID accountId, UUID clinicId, ClinicRole role, LocalDate startDate) {
		this.accountId = accountId;
		this.clinicId = clinicId;
		this.role = role;
		this.startDate = startDate;
	}

	/** Revocation, not deletion (FR-IAM-06): the row stays as history. */
	public void revoke(LocalDate endDate) {
		this.status = MemberStatus.REVOKED;
		this.endDate = endDate;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public UUID getClinicId() {
		return clinicId;
	}

	public ClinicRole getRole() {
		return role;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public MemberStatus getStatus() {
		return status;
	}

}
