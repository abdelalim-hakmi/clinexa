package com.clinexa.identity.authentication;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.clinexa.shared.security.identity.CurrentUser;
import com.clinexa.shared.security.identity.ClinicRole;

/**
 * What a signed-in caller is told about itself (FR-IAM-07): its identity, and the clinics it may
 * act in with the roles it holds in each.
 * <p>
 * This is what lets the SPA know which {@code clinicId} to put in its URLs. It is <strong>not a
 * right</strong>: every route still goes through L1, which re-reads the members and re-checks the
 * clinic of the path. A client that lied to itself about this payload would get a {@code 403}.
 * <p>
 * It contains no password, no other account, and nothing about a clinic the caller is not part of.
 *
 * @param clinics sorted, so two calls compare and a diff in a test is readable
 */
public record MeDto(UUID accountId, String email, List<ClinicDto> clinics) {

	/** One clinic the caller belongs to. Several roles in one clinic is the normal case. */
	public record ClinicDto(UUID clinicId, Set<ClinicRole> roles) {
	}

	public static MeDto of(CurrentUser user) {
		List<ClinicDto> clinics = user.assignments()
			.entrySet()
			.stream()
			.map(entry -> new ClinicDto(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparing(clinic -> clinic.clinicId().toString()))
			.toList();
		return new MeDto(user.accountId(), user.subject(), clinics);
	}

}
