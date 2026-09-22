package com.clinexa.identity.architecture;

import org.junit.jupiter.api.Test;

import com.clinexa.shared.architecture.fixtures.ArchitectureRules;
import com.clinexa.shared.security.fixtures.InventoryRules;
import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * Package structure for {@code identity-service} — kept apart from
 * {@link com.clinexa.identity.security.IdentityInventoryTest}: that class checks ratified security
 * invariants (INV-1..6); this one checks a modularity convention (package-by-feature) that carries
 * no INV number and applies the same way to every domain service.
 * <p>
 * Needs no Spring context: like {@code ArchitectureRulesTest} in {@code shared}, this is a plain
 * bytecode check, so it runs in milliseconds and needs no Docker.
 */
class IdentityArchitectureTest {

	private static final String PACKAGE = "com.clinexa.identity";

	private static final JavaClasses CLASSES = InventoryRules.classesOf(PACKAGE);

	@Test
	void noCyclesBetweenFeaturePackages() {
		ArchitectureRules.noCyclesBetweenFeaturePackages(PACKAGE).check(CLASSES);
	}

}
