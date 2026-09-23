package com.clinexa.shared.architecture.fixtures;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * Structural rules — what the <em>package layout</em> must look like, independent of any security
 * property. Kept in its own package rather than alongside {@code shared}'s
 * {@code security.fixtures} (six ratified security invariants, INV-2..6,
 * {@code Clinexa-vault/Security/MVP/04-tests-et-criteres-dacceptation.md}): this rule carries no INV number and applies the same way to
 * every domain service, so it does not belong under a package {@code shared}'s own CLAUDE.md scopes
 * to "security primitives and nothing else".
 */
public final class ArchitectureRules {

	private ArchitectureRules() {
	}

	/**
	 * Package-by-feature — no cyclic dependency between a service's top-level feature packages
	 * (one slice per first-level package under {@code basePackage}: {@code account},
	 * {@code member}, {@code record}, and so on).
	 * <p>
	 * A service organised by feature rather than by layer only stays that way as long as two
	 * features never depend on each other in both directions. That is what turns "features" back
	 * into one tangled layer with extra folders — the shared kernel (a service's {@code common}
	 * package) included: the moment it depends back on a feature that depends on it, it is no
	 * longer a kernel, it is one more node in the cycle.
	 * <p>
	 * Two blind spots, both inherent to slicing on one package level rather than a defect here: a
	 * class declared directly in {@code basePackage} (no feature subpackage of its own, e.g. the
	 * {@code @SpringBootApplication} class) joins no slice and is invisible to this check; and a
	 * <strong>one-directional</strong> reach into another feature's internals — a repository or
	 * entity used directly, with nothing closing the loop back — is not a cycle, so this rule does
	 * not catch it, only mutual coupling. Call it once per level that actually branches into
	 * siblings worth keeping apart: {@code CareArchitectureTest} also calls this a second time on
	 * {@code com.clinexa.care.record} alone, one level deeper, for the administrative/clinical split
	 * that INV-5 already treats as sensitive.
	 *
	 * @param basePackage e.g. {@code com.clinexa.care} — one slice per package directly beneath it
	 */
	public static ArchRule noCyclesBetweenFeaturePackages(String basePackage) {
		return SlicesRuleDefinition.slices()
			.matching(basePackage + ".(*)..")
			.should()
			.beFreeOfCycles()
			.allowEmptyShould(true)
			.as("package-by-feature — no cyclic dependency between the top-level packages of " + basePackage);
	}

}
