package com.clinexa.shared.architecture.fixtures.samples.cyclic.alpha;

import com.clinexa.shared.architecture.fixtures.samples.cyclic.beta.Beta;

/**
 * Fixture for {@code ArchitectureRulesTest} only: depends on {@code beta}, which depends back on
 * this package on purpose, so the pair is a cycle {@code noCyclesBetweenFeaturePackages} must catch.
 */
public class Alpha {

	Beta beta;

}
