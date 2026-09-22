package com.clinexa.identity.member.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.clinexa.shared.security.assignment.AssignmentsProvider;
import com.clinexa.shared.security.assignment.AssignmentsResponse;
import com.clinexa.shared.security.identity.ClinicRole;

/**
 * The server side of {@code SEC-10}: which clinics an account may act in, and with which roles.
 * <p>
 * This is the read that the {@code Member} exemption from {@code @TenantId} makes possible
 * ({@code SEC-13}): it is by {@code account_id}, and it necessarily crosses clinics — an account
 * does not yet have a current clinic when it is asked.
 * <p>
 * <strong>It is never routed by the gateway</strong> (LLD 21 §8, and the gateway's route file says
 * so explicitly). It is reachable on the internal network only. Service-to-service authentication
 * (mTLS) is deliberately out of scope at the foundation — the local Docker network is treated as
 * trusted — and that shortcut is <strong>recorded to be reopened before production</strong>
 * ({@code SEC-08}, durcissement §6). It is a dated decision, not an omission.
 * <p>
 * It exposes nothing else: no name, no email, no status — only clinic identifiers and roles. An
 * internal API that starts returning "just this one more field" becomes a second, unguarded read
 * path into the identity data.
 */
@RestController
class InternalAssignmentsController {

	private final AssignmentsProvider assignments;

	InternalAssignmentsController(AssignmentsProvider assignments) {
		this.assignments = assignments;
	}

	@GetMapping("/internal/accounts/{accountId}/assignments")
	AssignmentsResponse assignments(@PathVariable UUID accountId) {
		Map<UUID, Set<ClinicRole>> byClinic = this.assignments.assignmentsOf(accountId);
		List<AssignmentsResponse.ClinicAssignment> rows = byClinic.entrySet()
			.stream()
			.map(entry -> new AssignmentsResponse.ClinicAssignment(entry.getKey(), entry.getValue()))
			.toList();
		// An unknown account gets an empty list, not a 404: telling a caller that an account id does
		// not exist would turn this route into an existence oracle for account identifiers.
		return new AssignmentsResponse(accountId, rows);
	}

}
