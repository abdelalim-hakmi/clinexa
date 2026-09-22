package com.clinexa.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.clinexa.identity.support.IdentityContainers;
import com.clinexa.shared.security.web.SecurityChainBuilder;

/**
 * {@code GET /api/v1/auth/csrf} — the route that makes the sign-in reachable from a browser at all.
 * <p>
 * <strong>Why it is a class of its own, with its own context.</strong> Spring Security Test's
 * {@code csrf()} post-processor installs a token repository of its own for the remaining life of
 * the application context. Any class that uses it — {@code AuthenticationTest},
 * {@code IdentityAuthorizationMatrixTest} — therefore changes what a later request sees: no cookie
 * written, and a different header name. Asserting the real behaviour requires a context that no
 * post-processor has touched, and that is the only reason this class exists separately. An extra
 * property gives it its own entry in the context cache.
 * <p>
  * What it pins down, and why each half matters:
 * <ul>
 * <li><strong>A cookie the JavaScript can read.</strong> The SPA has to copy it into a header; an
 * {@code HttpOnly} cookie here would make CSRF impossible to satisfy from the page.</li>
 * <li><strong>A body carrying the same, RAW token.</strong> Clients with no cookie jar read it from
 * there — and the raw form is the one the chain checks the header against, not the XOR-masked form
 * a plain HTML form posts.</li>
 * </ul>
 */
@SpringBootTest(properties = { "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
		"spring.config.import=file:../../platform/config-server/src/main/resources/configurations/identity-service.yml",
		"clinexa.outbox.relay.enabled=false", "clinexa.test.context=csrf-bootstrap" })
@AutoConfigureMockMvc
@Import(IdentityContainers.class)
class CsrfBootstrapTest {

	@Autowired
	MockMvc mvc;

	@Test
	void theCsrfRouteAnswers200WithABody() throws Exception {
		MvcResult result = this.mvc.perform(get("/api/v1/auth/csrf")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString())
			.as("a client with no cookie jar must be able to read the token here")
			.contains("\"headerName\":\"" + SecurityChainBuilder.CSRF_HEADER + "\"")
			.contains("\"token\":");
	}

	@Test
	void theCsrfRouteSetsACookieTheSpaCanRead() throws Exception {
		MvcResult result = this.mvc.perform(get("/api/v1/auth/csrf")).andReturn();

		var cookie = result.getResponse().getCookie(SecurityChainBuilder.CSRF_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie.getValue()).isNotBlank();
		assertThat(cookie.isHttpOnly()).as("the SPA must be able to read it to copy it into a header").isFalse();
	}

	/** It is public: with no token, sign-in is impossible, so the token could never be obtained otherwise. */
	@Test
	void theCsrfRouteIsReachableWithNoSession() throws Exception {
		assertThat(this.mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getStatus())
			.isNotIn(401, 403);
	}

	/**
	 * The body carries the <strong>raw</strong> token, the one in the cookie — not its masked form.
	 * <p>
	 * Both exist and are not used at the same place: the masked form is what an HTML form posts as
	 * the {@code _csrf} parameter, while the header is compared against the raw token. Returning the
	 * masked one here would hand clients a token the chain then rejects, and the rejection would be
	 * a {@code 403} that explains nothing. The one assertion that closes that trap is this one: the
	 * body and the cookie must carry the same value.
	 */
	@Test
	void theBodyCarriesTheSameTokenAsTheCookie() throws Exception {
		MvcResult result = this.mvc.perform(get("/api/v1/auth/csrf")).andReturn();

		String fromCookie = result.getResponse().getCookie(SecurityChainBuilder.CSRF_COOKIE).getValue();
		assertThat(result.getResponse().getContentAsString())
			.as("the body must carry the raw token, the one the X-XSRF-TOKEN header must equal")
			.contains("\"token\":\"" + fromCookie + "\"");
	}

	/** And it really works: a POST carrying this token is not refused for CSRF reasons. */
	@Test
	void theBodyTokenIsAcceptedByTheChain() throws Exception {
		MvcResult bootstrap = this.mvc.perform(get("/api/v1/auth/csrf")).andReturn();
		String token = bootstrap.getResponse().getCookie(SecurityChainBuilder.CSRF_COOKIE).getValue();

		int status = this.mvc
			.perform(post("/api/v1/auth/logout").header(SecurityChainBuilder.CSRF_HEADER, token)
				.cookie(bootstrap.getResponse().getCookies()))
			.andReturn()
			.getResponse()
			.getStatus();

		// 401: no session — but crucially NOT 403, which would signal a CSRF refusal.
		assertThat(status).as("a 403 here would mean the token served is not the one expected").isEqualTo(401);
	}

}
