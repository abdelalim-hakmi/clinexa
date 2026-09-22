package com.clinexa.care.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.clinexa.care.support.CareIntegrationTest;
import com.clinexa.shared.security.fixtures.AuthorizationMatrix;
import com.clinexa.shared.security.fixtures.InventoryRules;
import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * <strong>F6 — Inventory</strong> for {@code care-service}: INV-1 to INV-6.
 * <p>
 * Tests on the code rather than on a behaviour — "this rule cannot be worked around tomorrow", not
 * "it holds today". On this service two of them carry most of the weight: INV-2, because every
 * business table here is a tenant table and <strong>none</strong> of them is exempted (the whitelist
 * is empty, unlike {@code identity-service}'s single entry), and INV-5, because the whole point of
 * separating administrative from clinical is that the separation cannot be undone by a field.
 */
@CareIntegrationTest
class CareInventoryTest {

	private static final String PACKAGE = "com.clinexa.care";

	private static final JavaClasses CLASSES = InventoryRules.classesOf(PACKAGE);

	@Autowired
	ApplicationContext context;

	// INV-1 — every exposed endpoint is declared in the matrix. On a skeleton bounded to two reads
	// (SEC-14), this is also what stops the perimeter from growing quietly.
	@Test
	void inv1EveryExposedEndpointIsInTheMatrix() {
		assertThat(exposedEndpoints()).isNotEmpty()
			.allSatisfy(endpoint -> assertThat(AuthorizationMatrix.declaredRoutesAndVerbs())
				.as("%s is not in _docs/security/authorization-matrix.md nor in matrice.csv."
						+ " On this skeleton, any extra route also requires an ADR (SEC-14).", endpoint)
				.contains(endpoint));
	}

	@Test
	void inv1TheMatrixDoesNotDeclareAVanishedRoute() {
		assertThat(AuthorizationMatrix.declaredRoutesAndVerbs()).allSatisfy(declared -> assertThat(exposedEndpoints())
			.as("%s is declared in matrice.csv but no longer exists in the code", declared)
			.contains(declared));
	}

	// SEC-14, checked rather than trusted: two reads, and no write route at all.
	@Test
	void sec14TheSkeletonHasNoWriteRoute() {
		assertThat(exposedEndpoints()).allSatisfy(endpoint -> assertThat(endpoint)
			.as("the care skeleton only exposes reads (SEC-14)")
			.startsWith("GET "));
	}

	// INV-2 — no exemption here. identity-service has exactly one (Member, SEC-13); this service
	// has none, and an empty whitelist is the strongest form of the rule.
	@Test
	void inv2EveryTenantEntityCarriesTenantId() {
		InventoryRules.tenantAnnotatedEntities(Set.of()).check(CLASSES);
	}

	// INV-3 — no native query and no bulk JPQL at all: this service has no exemption to declare.
	@Test
	void inv3NoUndeclaredNativeQuery() {
		InventoryRules.noUndeclaredNativeQuery(PACKAGE + ".NoExemption").check(CLASSES);
	}

	@Test
	void inv3NoNativeSqlIssuedDirectly() {
		InventoryRules.noDirectNativeSql().check(CLASSES);
	}

	@Test
	void inv4NoRoleHierarchyInTheCode() {
		InventoryRules.noRoleHierarchy().check(CLASSES);
	}

	@Test
	void inv4NoRoleHierarchyInTheContext() {
		assertThat(this.context
			.getBeanNamesForType(org.springframework.security.access.hierarchicalroles.RoleHierarchy.class))
			.as("a RoleHierarchy would silently hand the clinical record to the administrative role (I6)")
			.isEmpty();
	}

	// INV-5 — the administrative package must not depend on the clinical one. It catches the field
	// leak at build time instead of in a response, which is the only moment it is cheap to fix.
	@Test
	void inv5NoAdministrativeDtoReferencesTheClinicalOne() {
		InventoryRules
			.noClinicalLeakIntoAdministrative("..record.administrative..", "..record.clinical..")
			.check(CLASSES);
	}

	// INV-6 — only CurrentUser gives access to the identity. No exception at all on this service:
	// it does not own the authentication mechanism, so nothing here has a reason to read it.
	@Test
	void inv6OnlyCurrentUserGivesAccessToTheIdentity() {
		InventoryRules.identityContractRespected(Set.of()).check(CLASSES);
	}

	/** {@code VERB /path} for every endpoint, with variables normalised to {@code {}}. */
	private Set<String> exposedEndpoints() {
		RequestMappingHandlerMapping mapping = this.context.getBean("requestMappingHandlerMapping",
				RequestMappingHandlerMapping.class);
		return mapping.getHandlerMethods()
			.keySet()
			.stream()
			.flatMap(CareInventoryTest::expand)
			// Spring's own error dispatch, not an endpoint anyone routes to.
			.filter(endpoint -> !endpoint.endsWith(" /error"))
			.collect(Collectors.toUnmodifiableSet());
	}

	private static Stream<String> expand(RequestMappingInfo info) {
		Set<String> paths = info.getPathPatternsCondition() == null ? Set.of()
				: info.getPathPatternsCondition()
					.getPatterns()
					.stream()
					.map(pattern -> Pattern.compile("\\{[^}]+}").matcher(pattern.getPatternString()).replaceAll("{}"))
					.collect(Collectors.toSet());
		Set<String> verbs = info.getMethodsCondition().getMethods().isEmpty() ? Set.of("GET")
				: info.getMethodsCondition().getMethods().stream().map(Enum::name).collect(Collectors.toSet());
		return paths.stream().flatMap(path -> verbs.stream().map(verb -> verb + " " + path));
	}

}
