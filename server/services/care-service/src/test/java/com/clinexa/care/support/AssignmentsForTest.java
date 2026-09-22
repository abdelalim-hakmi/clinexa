package com.clinexa.care.support;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.clinexa.shared.security.assignment.AssignmentsProvider;
import com.clinexa.shared.security.assignment.AssignmentsUnavailableException;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.identity.ClinicRole;

/**
 * Stands in for {@code identity-service} in the tests that are not about the call itself.
 * <p>
 * It answers from {@link SecurityFixtures}, which is the same data the real service would hold —
 * the {@code SEC-10} wire contract is exercised for real, against a real HTTP server, in
 * {@code AssignmentsUnavailableTest}. Here the point is the <em>rest</em> of the chain: L1, the
 * ORM filter, the matrix.
 * <p>
 * {@link #unavailable} makes the source fail on demand. A fail-open never shows itself while
 * everything works — something has to be broken on purpose for criterion 12 to mean anything.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AssignmentsForTest {

	/** Flipped by a test to simulate an unreachable {@code identity-service}. */
	public static volatile boolean unavailable = false;

	@Bean
	@Primary
	AssignmentsProvider assignmentsForTest() {
		return accountId -> {
			if (unavailable) {
				throw new AssignmentsUnavailableException(
						new IllegalStateException("identity-service unreachable (simulated)"));
			}
			return SecurityFixtures.accounts()
				.stream()
				.filter(account -> account.id().equals(accountId))
				.findFirst()
				.<Map<UUID, Set<ClinicRole>>>map(account -> Map.copyOf(account.assignments()))
				.orElseGet(Map::of);
		};
	}

}
