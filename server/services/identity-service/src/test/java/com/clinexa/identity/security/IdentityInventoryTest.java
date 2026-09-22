package com.clinexa.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.clinexa.identity.support.IdentityTestSupport;
import com.clinexa.shared.security.fixtures.AuthorizationMatrix;
import com.clinexa.shared.security.fixtures.InventoryRules;
import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * <strong>F6 — Inventory</strong> for {@code identity-service}: INV-1 to INV-6.
 * <p>
 * These are tests on the <em>code</em>, not on a behaviour. They do not check that a rule holds
 * today but that it cannot be worked around tomorrow — including by someone who has never opened the
 * security folder. That is the only mechanism here that survives a change of team, and it is why the
 * ArchUnit dependency is justified by these six rules alone.
 * <p>
 * INV-1 is proved to actually fail in {@code InventoryIntentionallyFailsTest}: an inventory test
 * nobody has ever seen turn red is a test nobody knows works.
 */
@IdentityTestSupport
class IdentityInventoryTest {

	private static final String PACKAGE = "com.clinexa.identity";

	private static final JavaClasses CLASSES = InventoryRules.classesOf(PACKAGE);

	@Autowired
	ApplicationContext context;

	// INV-1 — every exposed endpoint is declared in the matrix. Catches the route added without
	// updating it, including the debug and export endpoints nobody thinks of as routes.
	@Test
	void inv1EveryExposedEndpointIsInTheMatrix() {
		assertThat(exposedEndpoints()).isNotEmpty()
			.allSatisfy(endpoint -> assertThat(AuthorizationMatrix.declaredRoutesAndVerbs())
				.as("%s is not in _docs/securite/matrice-autorisation.md nor in matrice.csv."
						+ " The matrix precedes the code: update it, then rerun.", endpoint)
				.contains(endpoint));
	}

	// The other direction: a matrix line whose route no longer exists is a stale rule that would
	// make F2 pass for the wrong reason — the route answers 404, which is "not refused".
	@Test
	void inv1TheMatrixDoesNotDeclareAVanishedRoute() {
		assertThat(AuthorizationMatrix.declaredRoutesAndVerbs()).allSatisfy(declared -> assertThat(exposedEndpoints())
			.as("%s is declared in matrice.csv but no longer exists in the code", declared)
			.contains(declared));
	}

	// INV-2 — SEC-13 grants the @TenantId exemption to Member, and to nothing else. The whitelist
	// is written here, in one place, so a second name added to it is a visible diff.
	@Test
	void inv2EveryTenantEntityCarriesTenantId() {
		InventoryRules.tenantAnnotatedEntities(Set.of("Member")).check(CLASSES);
	}

	// INV-3 — the outbox relay is the one declared derogation; anything else native or bulk fails.
	@Test
	void inv3NoUndeclaredNativeQuery() {
		InventoryRules.noUndeclaredNativeQuery(PACKAGE + ".outbox.TenantOverride").check(CLASSES);
	}

	// INV-3, second half — nothing issues native SQL directly (EntityManager, JdbcTemplate).
	@Test
	void inv3NoNativeSqlIssuedDirectly() {
		InventoryRules.noDirectNativeSql().check(CLASSES);
	}

	// INV-4 — no RoleHierarchy in the code...
	@Test
	void inv4NoRoleHierarchyInTheCode() {
		InventoryRules.noRoleHierarchy().check(CLASSES);
	}

	// ... and none in the running context either: a bean could arrive from a starter rather than
	// from a class of ours, and the rule would not see it.
	@Test
	void inv4NoRoleHierarchyInTheContext() {
		assertThat(this.context
			.getBeanNamesForType(org.springframework.security.access.hierarchicalroles.RoleHierarchy.class))
			.as("a RoleHierarchy would silently hand the clinical role to the administrative one (I6)")
			.isEmpty();
	}

	// INV-5 — no clinical type at all in this service: identity holds no clinical data by design
	// (HLD §3.1, "never does: contain clinical or financial data").
	@Test
	void inv5IdentityKnowsNoClinicalType() {
		assertThat(CLASSES.stream().map(clazz -> clazz.getName()).filter(name -> name.contains("clinical")).toList())
			.as("identity-service contains no clinical data")
			.isEmpty();
	}

	// INV-6 — only CurrentUser gives access to the identity. The exceptions are named: the
	// authentication package IS the mechanism, and the shared resolver is its single entry point.
	@Test
	void inv6OnlyCurrentUserGivesAccessToTheIdentity() {
		InventoryRules.identityContractRespected(Set.of(PACKAGE + ".authentication.AuthenticationController"))
			.check(CLASSES);
	}

	/** {@code VERB /path} for every endpoint, with variables normalised to {@code {}}. */
	private Set<String> exposedEndpoints() {
		RequestMappingHandlerMapping mapping = this.context.getBean("requestMappingHandlerMapping",
				RequestMappingHandlerMapping.class);
		return mapping.getHandlerMethods()
			.keySet()
			.stream()
			.flatMap(IdentityInventoryTest::expand)
			// Spring's own error dispatch, not an endpoint anyone routes to.
			.filter(endpoint -> !endpoint.endsWith(" /error"))
			.collect(Collectors.toUnmodifiableSet());
	}

	private static java.util.stream.Stream<String> expand(RequestMappingInfo info) {
		Set<String> paths = info.getPathPatternsCondition() == null ? Set.of()
				: info.getPathPatternsCondition()
					.getPatterns()
					.stream()
					.map(pattern -> Pattern.compile("\\{[^}]+}").matcher(pattern.getPatternString()).replaceAll("{}"))
					.collect(Collectors.toSet());
		Set<String> verbs = info.getMethodsCondition().getMethods().isEmpty() ? Set.of("GET")
				: info.getMethodsCondition()
					.getMethods()
					.stream()
					.map(Enum::name)
					.collect(Collectors.toSet());
		return paths.stream().flatMap(path -> verbs.stream().map(verb -> verb + " " + path));
	}

}
