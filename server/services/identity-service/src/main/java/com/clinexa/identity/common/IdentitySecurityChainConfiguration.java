package com.clinexa.identity.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import com.clinexa.shared.security.SharedSecurityConfiguration;
import com.clinexa.shared.security.TenantJpaConfiguration;
import com.clinexa.shared.security.identity.ClinicRole;
import com.clinexa.shared.security.tenant.TenantFilter;
import com.clinexa.shared.security.web.SecurityChainBuilder;
import com.clinexa.shared.security.web.DenialHandler;

/**
 * The authorization matrix of {@code identity-service}, and the authentication mechanism it is the
 * only service to own.
 * <p>
 * The matrix is the executable form of {@code Clinexa-vault/Security/MVP/03-rbac-et-matrice-autorisation.md}, written
 * <strong>before</strong> this file: a matrix written afterwards only describes what the code
 * already does, while one written first is the specification the code and the tests are measured
 * against. {@code src/test/resources/matrice.csv} holds the same table in machine-readable form and
 * is the data source of F2 and INV-1.
 * <p>
 * Order matters and Spring does not warn about it: narrowest first, because a broad rule placed
 * before a narrow one masks it in silence. The last line — {@code anyRequest().denyAll()} — is not
 * written here at all: the shared builder appends it, so no service can ship without it (I1).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@Import({ SharedSecurityConfiguration.class, TenantJpaConfiguration.class })
class IdentitySecurityChainConfiguration {

	@Bean
	SecurityFilterChain chain(HttpSecurity http, TenantFilter tenantFilter, DenialHandler denialHandler)
			throws Exception {
		return SecurityChainBuilder.forChain(http, tenantFilter, denialHandler).matrix(matrix -> matrix
			// Sign-in, and the route that hands the SPA its CSRF token: the two public routes of
			// an otherwise fully closed service.
			.requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
			.permitAll()
			.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
			.permitAll()
			// Liveness only, and without a detailed version (durcissement §5).
			.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
			.permitAll()
			// SEC-10: the inter-service API, never routed by the gateway (LLD 21 §8). Service
			// authentication (mTLS) is deferred — the local Docker network is treated as trusted —
			// and that shortcut is recorded to be reopened before production (SEC-08).
			.requestMatchers(HttpMethod.GET, "/internal/accounts/*/assignments")
			.permitAll()
			// Signing out and asking who you are: any authenticated account, no clinic involved.
			.requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
			.authenticated()
			.requestMatchers(HttpMethod.GET, "/api/v1/me")
			.authenticated()
			// The team of a clinic: its administrator, and only for that clinic — which is L1's
			// answer, not this line's. "Being a CLINIC_ADMIN" and "being the administrator of
			// THIS clinic" are two different questions, and both have to be asked.
			.requestMatchers(HttpMethod.GET, "/api/v1/clinics/*/members", "/api/v1/clinics/*/members/*")
			.hasRole(ClinicRole.CLINIC_ADMIN.name())
		// Everything else — /actuator/** beyond health, and /api/v1/me/** which does not exist at
		// the foundation (SEC-02) — falls to the denyAll() the builder appends.
		).build();
	}

	/**
	 * BCrypt, with its default strength. It is deliberately slow: that is what makes an offline
	 * attack on a stolen table expensive rather than instantaneous.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(encoder);
		// Hides "this email does not exist" behind "bad credentials" — and still runs the hash
		// comparison, so an unknown email does not answer measurably faster than a wrong password.
		provider.setHideUserNotFoundExceptions(true);
		return new ProviderManager(provider);
	}

	/**
	 * Where the sign-in stores the security context: the {@code HttpSession}, which Spring Session
	 * backs with Redis. Declared explicitly because the login route saves the context by hand —
	 * since Spring Security 6 nothing does it implicitly, and that is precisely what keeps the
	 * per-request roles granted by L1 out of the stored session.
	 */
	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/**
	 * Session-fixation protection for the sign-in.
	 * <p>
	 * {@code sessionFixation().changeSessionId()} in the shared chain only protects a login done by a
	 * filter of the chain. Ours is a controller that saves the context itself, so nothing would
	 * rotate the id unless the controller asks: a session id planted before the sign-in would still
	 * be the one holding the victim's identity afterwards.
	 */
	@Bean
	SessionAuthenticationStrategy sessionAuthenticationStrategy() {
		return new ChangeSessionIdAuthenticationStrategy();
	}

}
