package com.clinexa.care.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import com.clinexa.shared.security.SharedSecurityConfiguration;
import com.clinexa.shared.security.TenantJpaConfiguration;
import com.clinexa.shared.security.identity.ClinicRole;
import com.clinexa.shared.security.tenant.TenantFilter;
import com.clinexa.shared.security.web.SecurityChainBuilder;
import com.clinexa.shared.security.web.DenialHandler;

/**
 * The authorization matrix of {@code care-service}, and nothing else.
 * <p>
 * It is the executable form of {@code _docs/security/authorization-matrix.md}, which was written
 * <strong>before</strong> this file — a matrix written after the configuration only describes what
 * the code already does; written before, it is the specification the code and the tests are measured
 * against. {@code src/test/resources/matrice.csv} carries the same table in machine-readable form,
 * and is the data source of F2 and of INV-1.
 * <p>
 * Two lines carry the central business rule: the clinical volet is {@code PRACTITIONER} only,
 * including in the face of {@code CLINIC_ADMIN}, while the administrative volet is open to
 * {@code RECEPTIONIST} and {@code PRACTITIONER}. {@code CLINIC_ADMIN} reaches neither — roles are
 * disjoint (I6).
 * <p>
 * The chain's last line, {@code anyRequest().denyAll()}, is not written here: the shared builder
 * appends it, so no service can ship without it (I1).
 * <p>
 * Order matters and Spring will not warn about it: narrowest first, because a broad rule placed
 * before a narrow one masks it in silence.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@Import({ SharedSecurityConfiguration.class, TenantJpaConfiguration.class })
class CareSecurityChainConfiguration {

	private static final String RECORD = "/api/v1/clinics/*/records/*";

	@Bean
	SecurityFilterChain chain(HttpSecurity http, TenantFilter tenantFilter, DenialHandler denialHandler)
			throws Exception {
		return SecurityChainBuilder.forChain(http, tenantFilter, denialHandler).matrix(matrix -> matrix
			// Liveness only, and without a detailed version (durcissement §5).
			.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
			.permitAll()
			// The clinical volet: PRACTITIONER, and PRACTITIONER alone.
			.requestMatchers(HttpMethod.GET, RECORD + "/clinical")
			.hasRole(ClinicRole.PRACTITIONER.name())
			// The administrative volet: the front desk and the practitioner, never the administrator.
			.requestMatchers(HttpMethod.GET, RECORD + "/administrative")
			.hasAnyRole(ClinicRole.RECEPTIONIST.name(), ClinicRole.PRACTITIONER.name())
		// Everything else — including /actuator/** beyond health, and /api/v1/me/** which does not
		// exist at the foundation (SEC-02) — falls to the denyAll() the builder appends.
		).build();
	}

}
