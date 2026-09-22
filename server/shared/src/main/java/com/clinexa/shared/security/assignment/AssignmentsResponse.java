package com.clinexa.shared.security.assignment;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.clinexa.shared.security.identity.ClinicRole;

/**
 * The body of {@code GET /internal/accounts/&#123;id&#125;/assignments} — the wire contract of
 * {@code SEC-10}, written once and used by both ends.
 * <p>
 * It lives in {@code shared} for the same reason the principal does: a contract that two services
 * must agree on is cheaper to keep correct in one file than in two. It carries no business type —
 * only identifiers and roles — so it stays inside the {@code SEC-11} boundary.
 * <p>
 * A list of pairs rather than a {@code Map<UUID, ...>}: a JSON object keyed by UUID is awkward to
 * evolve and to read in a log, and an account holding two roles in one clinic is the normal case
 * (guide 2.5, the manager who also works the front desk).
 *
 * @param accountId the account the roles belong to — echoed back so a mismatched response is visible
 * @param assignments one entry per clinic the account may act in, revoked memberships excluded
 */
public record AssignmentsResponse(UUID accountId, List<ClinicAssignment> assignments) {

	/**
	 * @param clinicId the clinic
	 * @param roles the roles held there — never empty, a clinic with no active role is not listed
	 */
	public record ClinicAssignment(UUID clinicId, Set<ClinicRole> roles) {
	}

	/** The shape {@link AssignmentsProvider} hands to the rest of the code. */
	public Map<UUID, Set<ClinicRole>> toMap() {
		return this.assignments.stream()
			.filter(a -> a.roles() != null && !a.roles().isEmpty())
			.collect(Collectors.toUnmodifiableMap(ClinicAssignment::clinicId, ClinicAssignment::roles,
					(first, second) -> {
						// Two entries for one clinic would mean a malformed response; merge rather
						// than throw, so a server-side bug never turns into a denial of service.
						return java.util.stream.Stream.concat(first.stream(), second.stream())
							.collect(Collectors.toUnmodifiableSet());
					}));
	}

}
