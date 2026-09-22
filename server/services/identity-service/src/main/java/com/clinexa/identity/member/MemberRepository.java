package com.clinexa.identity.member;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads of {@link Member} — the one entity exempted from {@code @TenantId} ({@code SEC-13}), and
 * therefore the one repository whose safety is <strong>not</strong> provided by the ORM.
 * <p>
 * The exemption exists because L1 must read the members of an account <em>before</em> the tenant is
 * known — that is what tells it which clinics the account may act in. The cost of the exemption is
 * paid here, in three rules that replace the filter Hibernate would otherwise apply:
 * <ol>
 * <li><strong>No access by bare identifier.</strong> There is no {@code findById} on this interface;
 * {@code JpaRepository} still declares one, which is why INV-2 whitelists exactly {@code [Member]}
 * and the cross tests below exist. The three allowed accesses are by {@code account_id} (for L1), by
 * the {@code clinic_id} of the {@code TenantContext} (a clinic's own team) and by the pair
 * {@code (id, clinic_id)} (detail, revocation).</li>
 * <li><strong>Every clinic-scoped query filters by hand</strong> — the {@code clinicId} parameter
 * comes from {@code TenantContext}, never from the request path.</li>
 * <li><strong>Every one of them has a cross test</strong>: carol@A can neither list, nor read, nor
 * revoke a member of B, and alice does not see bob's members.</li>
 * </ol>
 * Revoked members are excluded from the L1 read: a revocation has to be effective on the next
 * request, not at the next login.
 */
public interface MemberRepository extends JpaRepository<Member, UUID> {

	/**
	 * Access 1 — by account, for L1. The only one that legitimately crosses clinics.
	 * <p>
	 * "In force on {@code day}" is three conditions, not one: still {@code ACTIVE}, already started
	 * ({@code startDate <= day}) and not yet ended ({@code endDate} empty or {@code >= day}). Reading
	 * the status alone would grant a member starting next week today, and keep one whose
	 * {@code endDate} has passed. A plain JPQL read, so INV-3 has nothing to say about it.
	 */
	@Query("""
			select m from Member m
			where m.accountId = :accountId
			  and m.status = com.clinexa.identity.member.MemberStatus.ACTIVE
			  and m.startDate <= :day
			  and (m.endDate is null or m.endDate >= :day)
			""")
	List<Member> findEffectiveOn(@Param("accountId") UUID accountId, @Param("day") LocalDate day);

	/** Access 2 — a clinic's own team. {@code clinicId} comes from the TenantContext. */
	List<Member> findByClinicIdOrderByStartDateAsc(@Param("clinicId") UUID clinicId);

	/** Access 3 — one member, in one clinic. Never {@code findById} alone. */
	Optional<Member> findByIdAndClinicId(UUID id, UUID clinicId);

}
