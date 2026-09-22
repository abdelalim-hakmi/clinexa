package com.clinexa.identity.member;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.clinexa.shared.security.assignment.AssignmentsProvider;
import com.clinexa.shared.security.identity.ClinicRole;

/**
 * {@code SEC-10} on the owning side: {@code identity-service} holds the {@code member} table, so it
 * reads it directly instead of calling itself over HTTP.
 * <p>
 * That is the whole shape of the decision — <strong>centralise the data, decentralise the
 * decision</strong>. The members live in one place; who may do what with them is answered locally,
 * by each service's own matrix, with no round trip per rule.
 * <p>
 * Only members in force today are returned: {@code ACTIVE} and inside their {@code startDate}/
 * {@code endDate} window. A revoked one is a row that stays as history (FR-IAM-06), never an
 * authorization.
 */
@Component
class AssignmentsLocalProvider implements AssignmentsProvider {

	private final MemberRepository members;

	AssignmentsLocalProvider(MemberRepository members) {
		this.members = members;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, Set<ClinicRole>> assignmentsOf(UUID accountId) {
		Map<UUID, Set<ClinicRole>> byClinic = new HashMap<>();
		for (Member member : this.members.findEffectiveOn(accountId, LocalDate.now())) {
			// An account may legitimately hold several roles in one clinic — the manager who also
			// works the front desk (guide 2.5). Stacked memberships, never a role that grows.
			byClinic.computeIfAbsent(member.getClinicId(), clinic -> EnumSet.noneOf(ClinicRole.class))
				.add(member.getRole());
		}
		byClinic.replaceAll((clinic, roles) -> Set.copyOf(roles));
		return Map.copyOf(byClinic);
	}

}
