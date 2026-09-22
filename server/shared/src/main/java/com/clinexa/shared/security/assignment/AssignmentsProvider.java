package com.clinexa.shared.security.assignment;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.clinexa.shared.security.identity.ClinicRole;

/**
 * How a service learns which clinics an account belongs to, and with which roles — the contract
 * ratified as {@code SEC-10}.
 * <p>
 * It exists because "the memberships are read from the database" is true for {@code identity-service},
 * which owns that table, and <strong>false for every other service</strong>: one database per
 * service. Without a written answer each service would have invented its own way of obtaining them,
 * recreating exactly the drift {@code CurrentUser} exists to prevent.
 * <p>
 * The principle behind the split: <strong>centralize the data</strong> (who is who, with which role
 * — it lives once, in {@code identity-service}) and <strong>decentralize the decision</strong>
 * (does this request pass — answered locally, by each service's own matrix).
 * <p>
 * Two implementations are foreseen, and the interface is what makes the second one cheap:
 * <ul>
 * <li><strong>O3, today</strong> — a synchronous call to {@code identity-service}
 * ({@link AssignmentsHttpProvider}), or a direct read for the service that owns the table;</li>
 * <li><strong>O4, later</strong> — a local replica fed by the {@code member.*} events already
 * published through the outbox (guide 8.5). Switching rewrites this implementation, and no caller.
 * </li>
 * </ul>
 * Watch for the trigger written into {@code SEC-10}: if the p95 latency of the synchronous call
 * starts eating a calling service's {@code NFR-PERF} budget, that is when O4 stops being optional.
 *
 * @see AssignmentsUnavailableException for the fail-closed rule when the source is unreachable
 */
@FunctionalInterface
public interface AssignmentsProvider {

	/**
	 * The clinics this account may act in, with the roles it holds in each. Revoked memberships are
	 * never returned — a revocation must be effective on the next request, not on the next login.
	 *
	 * @throws AssignmentsUnavailableException when the source of truth cannot be reached; the
	 * caller must refuse, never continue unfiltered (guide 8.3, criterion 12)
	 */
	Map<UUID, Set<ClinicRole>> assignmentsOf(UUID accountId);

}
