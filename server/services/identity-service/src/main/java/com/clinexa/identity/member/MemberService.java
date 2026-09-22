package com.clinexa.identity.member;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.clinexa.identity.outbox.EventPublisher;
import com.clinexa.shared.security.identity.ClinicRole;
import com.clinexa.shared.security.tenant.TenantContext;

/**
 * Everything that reads or changes a member, and the place where the {@code SEC-13} exemption is
 * actually paid for.
 * <p>
 * Because {@link Member} carries no {@code @TenantId}, nothing in the ORM stops a query from
 * reaching another clinic's rows. So every method here takes its clinic from
 * {@link TenantContext} — which L1 filled after validating it against the caller's members — and
 * never from a path variable. An identifier that came from the request is not trusted (I4); one
 * that came from L1 has already been checked.
 * <p>
 * <strong>There is no write route at the foundation.</strong> These methods exist and are exercised
 * by tests, which is what {@code SEC-14} and guide 8.5 ask for: the three member events must be
 * published from V0 even without a consumer, because the marginal cost is nil today and it is what
 * makes the later switch to an event-driven replica (option O4 of {@code SEC-10}) almost free.
 */
@Service
public class MemberService {

	private final MemberRepository members;

	private final EventPublisher events;

	private final TenantContext tenantContext;

	public MemberService(MemberRepository members, EventPublisher events, TenantContext tenantContext) {
		this.members = members;
		this.events = events;
		this.tenantContext = tenantContext;
	}

	/** The team of the current clinic. Filtered by hand — there is no ORM filter on this table. */
	@Transactional(readOnly = true)
	public List<Member> clinicTeam() {
		return this.members.findByClinicIdOrderByStartDateAsc(this.tenantContext.requireClinicId());
	}

	/** One member of the current clinic. Never {@code findById} alone: that would cross clinics. */
	@Transactional(readOnly = true)
	public Member member(UUID memberId) {
		return this.members.findByIdAndClinicId(memberId, this.tenantContext.requireClinicId())
			.orElseThrow(() -> new MemberNotFoundException(memberId));
	}

	/**
	 * Assigns an account to the current clinic.
	 * <p>
	 * {@code clinicId} is written explicitly and comes from the tenant context, so the row cannot
	 * land in a clinic the caller has no right to — the check the ORM performs for every other
	 * table.
	 */
	@Transactional
	public Member assign(UUID accountId, ClinicRole role, LocalDate startDate) {
		UUID clinicId = this.tenantContext.requireClinicId();
		Member member = this.members.save(new Member(accountId, clinicId, role, startDate));
		this.events.publish("identity.member.assigned", clinicId,
				Map.of("memberId", member.getId(), "accountId", accountId, "role", role.name()));
		return member;
	}

	/**
	 * Changes a role by revoking the member and creating another one.
	 * <p>
	 * Not an {@code UPDATE} of the role column, on purpose: a member is the trace of an
	 * authorization that was in force over a period. Overwriting it would erase the answer to
	 * "who could read this record last March", which is the whole point of keeping the row
	 * (FR-IAM-06).
	 */
	@Transactional
	public Member changeRole(UUID memberId, ClinicRole newRole, LocalDate effectiveOn) {
		requireImmediateEffect(effectiveOn);
		UUID clinicId = this.tenantContext.requireClinicId();
		Member previous = member(memberId);
		if (previous.getRole() == newRole) {
			return previous;
		}
		previous.revoke(effectiveOn);
		Member next = this.members.save(new Member(previous.getAccountId(), clinicId, newRole, effectiveOn));
		this.events.publish("identity.member.role_changed", clinicId,
				Map.of("memberId", next.getId(), "previousMemberId", previous.getId(), "accountId",
						previous.getAccountId(), "previousRole", previous.getRole().name(), "role", newRole.name()));
		return next;
	}

	/** Revocation, never deletion (FR-IAM-06): the row stays as history. */
	@Transactional
	public Member revoke(UUID memberId, LocalDate endDate) {
		requireImmediateEffect(endDate);
		UUID clinicId = this.tenantContext.requireClinicId();
		Member member = member(memberId);
		if (member.getStatus() == MemberStatus.REVOKED) {
			return member;
		}
		member.revoke(endDate);
		this.events.publish("identity.member.revoked", clinicId,
				Map.of("memberId", member.getId(), "accountId", member.getAccountId(), "role",
						member.getRole().name()));
		return member;
	}

	/**
	 * A revocation or a role change takes effect now, or it is refused.
	 * <p>
	 * Both flip the status to {@code REVOKED} at once — which is what makes a revocation immediate
	 * (guide 4.4). Accepting a future date would record "until next month" while the old role is in
	 * fact gone today, and the replacement member would be granted today too. Future-dated changes
	 * would need a scheduled activation, which the foundation does not have; {@link #assign} is not
	 * concerned, since a member starting later is simply outside its window until then.
	 */
	private static void requireImmediateEffect(LocalDate date) {
		if (date.isAfter(LocalDate.now())) {
			throw new IllegalArgumentException(
					"A revocation or a role change takes effect immediately: " + date + " is in the future");
		}
	}

	/**
	 * A member of another clinic and a member that does not exist are indistinguishable from the
	 * outside: a {@code 404} either way. Answering {@code 403} for the first would confirm that the
	 * identifier exists somewhere (LLD 21 §3.2).
	 */
	public static class MemberNotFoundException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public MemberNotFoundException(UUID memberId) {
			super("No member " + memberId + " in this clinic");
		}

	}

}
