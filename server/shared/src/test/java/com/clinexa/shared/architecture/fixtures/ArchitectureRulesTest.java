package com.clinexa.shared.architecture.fixtures;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.importer.ClassFileImporter;

/**
 * {@link ArchitectureRules}, made to fail on purpose — same discipline as the shared module's own
 * {@code InventoryRulesTest} (guide 10.8): a rule nobody has seen turn red is a rule nobody knows
 * still works. The samples live in real, separate sub-packages ({@code samples.cyclic.*},
 * {@code samples.indirectcycle.*}, {@code samples.cyclefree.*}) because a slice rule reacts to
 * package structure, which nested classes sharing one package cannot fake.
 */
class ArchitectureRulesTest {

	private static final String CYCLIC = "com.clinexa.shared.architecture.fixtures.samples.cyclic";

	private static final String INDIRECT_CYCLE = "com.clinexa.shared.architecture.fixtures.samples.indirectcycle";

	private static final String CYCLE_FREE = "com.clinexa.shared.architecture.fixtures.samples.cyclefree";

	// Asserted against the rule's own label, not ArchUnit's internal violation wording ("Cycle
	// detected: ..."): that wording is an implementation detail of SliceRule's formatting and could
	// be reworded by a future ArchUnit version with no change to whether this rule still works.
	private static final String RULE_LABEL = "no cyclic dependency";

	@Test
	void catchesADirectCycleBetweenTwoFeaturePackages() {
		assertThatThrownBy(() -> ArchitectureRules.noCyclesBetweenFeaturePackages(CYCLIC)
			.check(new ClassFileImporter().importPackages(CYCLIC))).hasMessageContaining(RULE_LABEL);
	}

	// A pair that's directly mutual is the easiest shape to detect; this proves the same slice
	// pattern also catches a longer cycle where no two packages depend on each other directly.
	@Test
	void catchesAnIndirectCycleAcrossThreeFeaturePackages() {
		assertThatThrownBy(() -> ArchitectureRules.noCyclesBetweenFeaturePackages(INDIRECT_CYCLE)
			.check(new ClassFileImporter().importPackages(INDIRECT_CYCLE))).hasMessageContaining(RULE_LABEL);
	}

	@Test
	void letsThroughFeaturePackagesWithoutACycle() {
		assertThatCode(() -> ArchitectureRules.noCyclesBetweenFeaturePackages(CYCLE_FREE)
			.check(new ClassFileImporter().importPackages(CYCLE_FREE))).doesNotThrowAnyException();
	}

	// A brand-new service scaffolded with everything still directly in its base package (no feature
	// subpackage yet) produces zero slices. Without allowEmptyShould(true) that is ArchUnit's
	// "checked no classes" failure, not a pass — this proves it passes instead.
	@Test
	void passesTriviallyWhenNoFeaturePackageExistsYet() {
		assertThatCode(() -> ArchitectureRules.noCyclesBetweenFeaturePackages(CYCLE_FREE + ".nonexistent")
			.check(new ClassFileImporter().importPackages(CYCLE_FREE))).doesNotThrowAnyException();
	}

}
