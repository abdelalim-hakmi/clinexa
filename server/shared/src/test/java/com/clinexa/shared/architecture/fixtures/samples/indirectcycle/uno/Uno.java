package com.clinexa.shared.architecture.fixtures.samples.indirectcycle.uno;

import com.clinexa.shared.architecture.fixtures.samples.indirectcycle.dos.Dos;

/**
 * Fixture for {@code ArchitectureRulesTest} only: a 3-package indirect cycle
 * ({@code uno -> dos -> tres -> uno}), no pair of which is directly mutual — proves the rule catches
 * a longer cycle, not just the trivial 2-package case {@code cyclic.alpha}/{@code cyclic.beta} covers.
 */
public class Uno {

	Dos dos;

}
