package com.clinexa.care.architecture;

import org.junit.jupiter.api.Test;

import com.clinexa.shared.architecture.fixtures.ArchitectureRules;
import com.clinexa.shared.security.fixtures.InventoryRules;
import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * Package structure for {@code care-service} — kept apart from
 * {@link com.clinexa.care.security.CareInventoryTest}: that class checks ratified security
 * invariants (INV-1..6); this one checks a modularity convention (package-by-feature) that carries
 * no INV number and applies the same way to every domain service.
 * <p>
 * Needs no Spring context: like {@code ArchitectureRulesTest} in {@code shared}, this is a plain
 * bytecode check, so it runs in milliseconds and needs no Docker.
 */
class CareArchitectureTest {

	private static final String PACKAGE = "com.clinexa.care";

	private static final JavaClasses CLASSES = InventoryRules.classesOf(PACKAGE);

	@Test
	void noCyclesBetweenFeaturePackages() {
		ArchitectureRules.noCyclesBetweenFeaturePackages(PACKAGE).check(CLASSES);
	}

	// One level deeper than the top-level check: com.clinexa.care.record.(*).. slices administrative
	// and clinical apart on their own, which the top-level slicing can't see (both collapse into one
	// "record" slice there). This is the one pair the codebase already treats as sensitive (INV-5),
	// so it earns its own, more precise check rather than relying on the coarser top-level one.
	@Test
	void noCyclesBetweenAdministrativeAndClinicalRecordPackages() {
		ArchitectureRules.noCyclesBetweenFeaturePackages(PACKAGE + ".record").check(CLASSES);
	}

}
