package com.clinexa.shared.security.fixtures;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * The inventory rules — the most profitable tests of the whole foundation, and the only ones that
 * survive its authors.
 * <p>
 * They do not check a <em>behaviour</em>; they check the <strong>code itself</strong>. Not "the rule
 * is respected today" but "it cannot be worked around tomorrow, including by someone who has never
 * read the security folder". Every other test in this project describes an intention that happens to
 * hold; these describe one that cannot quietly stop holding.
 * <p>
 * They live here, written once, rather than twice in the two services: a rule kept in two copies
 * drifts, and the whole point of these is that they do not.
 *
 * @see #tenantAnnotatedEntities for the one place a whitelist is allowed, and why it has one entry
 */
public final class InventoryRules {

	private static final String QUERY = "org.springframework.data.jpa.repository.Query";

	private static final String NATIVE_QUERY = "org.springframework.data.jpa.repository.NativeQuery";

	private static final String MODIFYING = "org.springframework.data.jpa.repository.Modifying";

	private InventoryRules() {
	}

	/**
	 * <strong>INV-3, second half</strong> — no native SQL issued directly either.
	 * <p>
	 * {@link #noDirectNativeSql} watches repositories, but {@code @TenantId} is bypassed
	 * just as well by {@code EntityManager.createNativeQuery(...)} or a {@code JdbcTemplate} in a
	 * service, which no repository rule would ever see. Neither is used by the foundation; this rule
	 * is what keeps it that way. (Test code is not imported, and inserts fixtures with JDBC on
	 * purpose.)
	 */
	public static ArchRule noDirectNativeSql() {
		return ArchRuleDefinition.classes()
			.should(new ArchCondition<JavaClass>("not issue native SQL directly (EntityManager, JDBC)") {
				@Override
				public void check(JavaClass clazz, ConditionEvents events) {
					clazz.getMethodCallsFromSelf()
						.stream()
						.filter(call -> call.getName().startsWith("createNative"))
						.forEach(call -> events.add(SimpleConditionEvent.violated(clazz,
								call.getDescription() + ": the @TenantId filter does not apply to native SQL")));
					clazz.getDirectDependenciesFromSelf()
						.stream()
						.filter(dependency -> dependency.getTargetClass().getName().startsWith("org.springframework.jdbc.core."))
						.forEach(dependency -> events.add(SimpleConditionEvent.violated(clazz,
								dependency.getDescription() + ": direct JDBC bypasses the @TenantId filter")));
				}
			})
			.allowEmptyShould(true)
			.as("INV-3 — no native SQL issued directly (EntityManager, JdbcTemplate)");
	}

	/**
	 * <strong>INV-2</strong> — every entity carrying a {@code clinicId} carries {@code @TenantId}.
	 * <p>
	 * Catches the entity added six months from now without the annotation: it would be readable and
	 * writable across every clinic, and nothing at runtime would say so. It also catches a
	 * <em>second</em> exemption slipped into the whitelist, which is why the whitelist is passed in
	 * as a parameter and asserted separately: {@code SEC-13} grants the exemption to {@code Member}
	 * and to nothing else.
	 *
	 * @param whitelist simple class names allowed to carry {@code clinicId} without
	 * {@code @TenantId} — {@code [Member]} in {@code identity-service}, empty everywhere else
	 */
	public static ArchRule tenantAnnotatedEntities(Set<String> whitelist) {
		return ArchRuleDefinition.fields()
			.that()
			.haveName("clinicId")
			.and()
			.areDeclaredInClassesThat()
			.areAnnotatedWith("jakarta.persistence.Entity")
			.should(new ArchCondition<JavaField>("carry @TenantId, or appear in the SEC-13 whitelist") {
				@Override
				public void check(JavaField field, ConditionEvents events) {
					boolean annotated = field.isAnnotatedWith("org.hibernate.annotations.TenantId");
					boolean tolerated = whitelist.contains(field.getOwner().getSimpleName());
					if (annotated == tolerated) {
						events.add(SimpleConditionEvent.violated(field,
								annotated ? field.getFullName()
										+ " is in the SEC-13 whitelist yet carries @TenantId:"
										+ " the exemption no longer has a reason to exist"
										: field.getFullName() + " carries clinicId without @TenantId:"
												+ " it would be readable and writable in every clinic"));
					}
				}
			})
			.as("INV-2 — every entity carrying clinicId carries @TenantId, except " + whitelist);
	}

	/**
	 * <strong>INV-3</strong> — no native query and no bulk JPQL on a repository, unless the
	 * exemption is declared.
	 * <p>
	 * {@code @TenantId} does not filter native SQL: such a query reads every clinic. Guide 6.3
	 * allows an exemption provided it is named, written down and cross-tested — so the rule
	 * does not forbid it outright, it forbids an <strong>undeclared</strong> one. That distinction
	 * is the whole design: an exemption that must be named is one a reviewer can argue with.
	 *
	 * @param exemptionAnnotation fully qualified name of the annotation that declares one
	 */
	public static ArchRule noUndeclaredNativeQuery(String exemptionAnnotation) {
		return ArchRuleDefinition.methods()
			.that()
			.areDeclaredInClassesThat()
			.areAssignableTo("org.springframework.data.repository.Repository")
			.and(new DescribedPredicate<JavaMethod>("declare a query") {
				@Override
				public boolean test(JavaMethod method) {
					return method.isAnnotatedWith(QUERY) || method.isAnnotatedWith(NATIVE_QUERY)
							|| method.isAnnotatedWith(MODIFYING);
				}
			})
			.should(new ArchCondition<JavaMethod>("not bypass the tenant filter without a written exemption") {
				@Override
				public void check(JavaMethod method, ConditionEvents events) {
					// @NativeQuery is @Query(nativeQuery = true) under another name, and isAnnotatedWith
					// does not follow meta-annotations: it has to be asked for by name.
					boolean native_ = method.isAnnotatedWith(NATIVE_QUERY) || (method.isAnnotatedWith(QUERY)
							&& method.getAnnotationOfType(QUERY)
								.getProperties()
								.getOrDefault("nativeQuery", Boolean.FALSE)
								.equals(Boolean.TRUE));
					boolean modifying = method.isAnnotatedWith(MODIFYING);
					if (!native_ && !modifying) {
						return;
					}
					if (!method.isAnnotatedWith(exemptionAnnotation)) {
						events.add(SimpleConditionEvent.violated(method, method.getFullName()
								+ " is a native or bulk query with no @DerogationTenant:"
								+ " the @TenantId filter does not apply to it, it would read every clinic"));
					}
				}
			})
			.allowEmptyShould(true)
			.as("INV-3 — no native or bulk query without a declared exemption");
	}

	/**
	 * <strong>INV-4</strong> — no {@code RoleHierarchy} anywhere.
	 * <p>
	 * Catches the well-meant simplification that writes {@code CLINIC_ADMIN > PRACTITIONER >
	 * RECEPTIONIST} and thereby hands the clinical record to the administrative role, without a
	 * single line saying so. Roles are disjoint (I6, {@code SEC-06}); a legitimate accumulation of
	 * rights is an accumulation of memberships.
	 */
	public static ArchRule noRoleHierarchy() {
		return ArchRuleDefinition.noClasses()
			.should()
			.dependOnClassesThat(new DescribedPredicate<JavaClass>("are a Spring Security RoleHierarchy") {
				@Override
				public boolean test(JavaClass clazz) {
					return clazz.getName().startsWith("org.springframework.security.access.hierarchicalroles.");
				}
			})
			.allowEmptyShould(true)
			.as("INV-4 — no role hierarchy (I6, SEC-06)");
	}

	/**
	 * <strong>INV-5</strong> — no administrative DTO references a clinical type.
	 * <p>
	 * Turns a field leak into a compilation-visible mistake rather than a runtime one. The packages
	 * are the boundary, which is why the clinical volet lives in its own package and its own table.
	 *
	 * @param administrativePackage e.g. {@code ..record.administrative..}
	 * @param clinicalPackage e.g. {@code ..record.clinical..}
	 */
	public static ArchRule noClinicalLeakIntoAdministrative(String administrativePackage, String clinicalPackage) {
		return ArchRuleDefinition.noClasses()
			.that()
			.resideInAPackage(administrativePackage)
			.should()
			.dependOnClassesThat()
			.resideInAPackage(clinicalPackage)
			.allowEmptyShould(true)
			.as("INV-5 — no administrative type depends on the clinical one");
	}

	/**
	 * <strong>INV-6</strong> — nobody reads the authentication mechanism directly.
	 * <p>
	 * {@code Authentication}, {@code Jwt} and {@code HttpSession} are the three ways to bypass
	 * {@code CurrentUser}, and bypassing it is what would make the migration of {@code SEC-12} a
	 * rewrite of every service instead of a version bump. The exceptions are named, not generic: the
	 * authentication package of {@code identity-service} <em>is</em> the mechanism, and the shared
	 * resolver is the single construction point.
	 *
	 * @param exceptions fully qualified class names allowed to touch them
	 */
	public static ArchRule identityContractRespected(Set<String> exceptions) {
		return ArchRuleDefinition.noClasses()
			.that(new DescribedPredicate<JavaClass>("are not the authentication mechanism itself") {
				@Override
				public boolean test(JavaClass clazz) {
					// A nested or generated class shares the prefix of the class that declares it.
					return exceptions.stream().noneMatch(exception -> clazz.getName().startsWith(exception));
				}
			})
			.should()
			.dependOnClassesThat(new DescribedPredicate<JavaClass>(
					"are the Authentication, a Jwt or the HttpSession — reading the principal directly") {
				@Override
				public boolean test(JavaClass clazz) {
					String name = clazz.getName();
					return name.equals("org.springframework.security.core.Authentication")
							|| name.equals("org.springframework.security.core.context.SecurityContextHolder")
							|| name.startsWith("org.springframework.security.oauth2.jwt.Jwt")
							|| name.equals("jakarta.servlet.http.HttpSession");
				}
			})
			.allowEmptyShould(true)
			.as("INV-6 — only CurrentUser gives access to the identity");
	}

	/** Convenience: the classes of a service, without its test classes. */
	public static JavaClasses classesOf(String basePackage) {
		return new com.tngtech.archunit.core.importer.ClassFileImporter()
			.withImportOption(new com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests())
			.withImportOption(new com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeJars())
			.importPackages(basePackage);
	}

}
