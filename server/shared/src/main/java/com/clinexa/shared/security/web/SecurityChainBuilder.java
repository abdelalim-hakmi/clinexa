package com.clinexa.shared.security.web;

import java.util.function.Consumer;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import com.clinexa.shared.security.tenant.TenantFilter;

import jakarta.servlet.DispatcherType;

/**
 * Builds the filter chain every service shares, and <strong>appends {@code denyAll()} itself</strong>
 * so no service can forget it (I1).
 * <p>
 * That last line is the whole point: it is what makes a route added tomorrow, by someone who never
 * read the security folder, refused rather than public. A service declares only the cells of its own
 * matrix; it never gets a chance to write {@code anyRequest()}.
 * <p>
 * What is common — and therefore here rather than duplicated per service ({@code SEC-11}):
 * <ul>
 * <li><strong>CSRF on</strong>, in its SPA form ({@link SpaCsrfHandler}), with the
 * {@code XSRF-TOKEN} cookie readable by JavaScript and the session cookie not;</li>
 * <li><strong>the tenant filter</strong> (L1), placed just before authorization so that the roles
 * it grants for this clinic are the ones the matrix reads;</li>
 * <li><strong>the two refusals</strong> rendered as Problem Details ({@link DenialHandler});</li>
 * <li><strong>no HTTP Basic and no form login</strong> — the only way in is
 * {@code POST /api/v1/auth/login}, so an unauthenticated call fails as {@code 401} instead of
 * popping a browser credentials dialog in front of the SPA.</li>
 * </ul>
 * <strong>Anonymous authentication stays on</strong>, and that is a deliberate reversal of the
 * reflex to switch it off. It grants nothing — an anonymous token satisfies no rule, and
 * {@code denyAll()} is unmoved by it. What it does is let {@code ExceptionTranslationFilter} tell
 * "I do not know who you are" from "I know and you may not": with no anonymous token the security
 * context is {@code null}, which that filter reads as <em>not anonymous</em>, and it answers
 * {@code 403} to a caller who never authenticated. The three refusals of the foundation would then
 * be two, and the one that disappeared is the one that says "log in".
 * <p>
 * What is <em>not</em> here, on purpose: the matrix itself, the ownership rules and any knowledge of
 * a business domain. Those stay in each service (guide 4.5).
 */
public final class SecurityChainBuilder {

	private final HttpSecurity http;

	private final TenantFilter tenantFilter;

	private final DenialHandler denialHandler;

	private Consumer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> matrix =
			registry -> {};

	private SecurityChainBuilder(HttpSecurity http, TenantFilter tenantFilter, DenialHandler denialHandler) {
		this.http = http;
		this.tenantFilter = tenantFilter;
		this.denialHandler = denialHandler;
	}

	public static SecurityChainBuilder forChain(HttpSecurity http, TenantFilter tenantFilter,
			DenialHandler denialHandler) {
		return new SecurityChainBuilder(http, tenantFilter, denialHandler);
	}

	/**
	 * The service's own matrix, one rule per line, narrowest first.
	 * <p>
	 * Order matters and Spring will not warn: a broad rule written before a narrow one silently
	 * masks it (guide 7.1). Read the rules top-down as "first match wins".
	 */
	public SecurityChainBuilder matrix(
			Consumer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> matrix) {
		this.matrix = matrix;
		return this;
	}

	/**
	 * The cookie the SPA reads the CSRF token from. Readable by JavaScript on purpose — the page has
	 * to copy it into a header, which is precisely what a third-party site cannot do.
	 */
	public static final String CSRF_COOKIE = "XSRF-TOKEN";

	/** The header the SPA copies it into. */
	public static final String CSRF_HEADER = "X-XSRF-TOKEN";

	/**
	 * The CSRF repository, with both names written out rather than inherited.
	 * <p>
	 * They happen to match the defaults of this repository, and they are still spelled here because
	 * this is exactly the pair that produces an unexplainable {@code 403}: the client sends the
	 * token under one name, the server looks for another, and nothing in either log says so. Same
	 * family of trap as {@code hasRole("X")} versus {@code hasAuthority("ROLE_X")} — and it was a
	 * real one here, since a different default surfaced as soon as anything else in the chain
	 * supplied a token.
	 * <p>
	 * These two values are also Angular's built-in defaults, so the SPA needs no configuration of
	 * its own to match.
	 */
	private static CookieCsrfTokenRepository csrfRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieName(CSRF_COOKIE);
		repository.setHeaderName(CSRF_HEADER);
		return repository;
	}

	public SecurityFilterChain build() throws Exception {
		this.http
			.csrf(csrf -> csrf.csrfTokenRepository(csrfRepository()).csrfTokenRequestHandler(new SpaCsrfHandler()))
			// The session is created by the login and read from Redis by every service; nothing else
			// creates one, so an anonymous call leaves no trace in Redis.
			.sessionManagement(
					session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
							.sessionFixation(fixation -> fixation.changeSessionId())
			)
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.logout(logout -> logout.disable())
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(this.denialHandler)
				.accessDeniedHandler(this.denialHandler))
			.addFilterBefore(this.tenantFilter, AuthorizationFilter.class)
			.authorizeHttpRequests(registry -> {
				// The container's ERROR dispatch is not a request anyone made: without this line the
				// denyAll() below refuses /error and turns every 400 or 500 into a 403 role refusal.
				// It opens the dispatch type only — a request that names /error itself is still an
				// ordinary REQUEST and still falls to denyAll().
				registry.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
				this.matrix.accept(registry);
				// I1 — the line the whole foundation rests on. Not decorative, and not optional:
				// it is appended here so that no service can ship without it.
				registry.anyRequest().denyAll();
			});
		return this.http.build();
	}

}
