package com.clinexa.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.clinexa.identity.support.TestData;
import com.clinexa.identity.support.IdentityTestSupport;
import com.clinexa.shared.security.fixtures.SecurityFixtures;

import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.http.Cookie;

/**
 * <strong>F1 — Authentication</strong>, and the whole of acceptance criterion 1.
 * <p>
 * It runs against the real filter chain and a real Redis-backed session, with no test
 * post-processor injecting a principal: a test that hands itself an authenticated user proves the
 * controller works, not that the authentication does.
 */
@IdentityTestSupport
class AuthenticationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	TestData data;

	@BeforeEach
	void install() {
		this.data.install();
	}

	// Criterion 1, first half: a protected route with no session is 401 — never 403, which would
	// say "I know who you are and you may not", and never 200.
	@Test
	void withNoSessionAProtectedRouteAnswers401() throws Exception {
		this.mvc.perform(get("/api/v1/me")).andExpect(result -> assertThat(result.getResponse().getStatus())
			.isEqualTo(401));
	}

	// ... with a Problem Details body naming the refusal, so a log says which of the three it is.
	@Test
	void theRefusalIsAProblemDetails() throws Exception {
		MvcResult result = this.mvc.perform(get("/api/v1/me")).andReturn();
		assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
		assertThat(result.getResponse().getContentAsString()).contains("AUTH_NOT_AUTHENTICATED")
			.doesNotContain("alice");
	}

	// The sign-in works, and answers with the caller's own members (FR-IAM-07). erin is the fixture
	// to use here: she belongs to two clinics, so a response carrying only one would be wrong in a
	// way a single-clinic account could never reveal.
	@Test
	void signInOpensASessionAndSaysWhoYouAre() throws Exception {
		MvcResult signIn = signInAs(SecurityFixtures.ERIN.email(), SecurityFixtures.PASSWORD);

		assertThat(signIn.getResponse().getStatus()).isEqualTo(200);
		assertThat(signIn.getResponse().getContentAsString()).contains(SecurityFixtures.ERIN.id().toString())
			.contains(SecurityFixtures.CLINIC_A.toString())
			.contains(SecurityFixtures.CLINIC_B.toString())
			.as("the password hash never leaves the server")
			.doesNotContain("$2a$");

		MvcResult me = this.mvc.perform(get("/api/v1/me").cookie(cookies(signIn))).andReturn();
		assertThat(me.getResponse().getStatus()).isEqualTo(200);
	}

	// Criterion 1, second half: an invalidated session is 401 again, immediately. That is the return
	// on an opaque cookie rather than a self-contained token — there is nothing left to validate.
	@Test
	void anInvalidatedSessionAnswers401() throws Exception {
		Cookie[] session = cookies(signInAs(SecurityFixtures.ALICE.email(), SecurityFixtures.PASSWORD));
		assertThat(this.mvc.perform(get("/api/v1/me").cookie(session)).andReturn().getResponse().getStatus())
			.isEqualTo(200);

		assertThat(this.mvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrf()))
			.andReturn()
			.getResponse()
			.getStatus()).isEqualTo(204);

		assertThat(this.mvc.perform(get("/api/v1/me").cookie(session)).andReturn().getResponse().getStatus())
			.as("an invalidated session is no longer a session")
			.isEqualTo(401);
	}

	// A wrong password and an unknown email answer identically: this route must not become an oracle
	// telling an attacker which email addresses have an account.
	@Test
	void aFailureDoesNotSayWhetherTheAccountExists() throws Exception {
		MvcResult wrongPassword = signInAs(SecurityFixtures.ALICE.email(), "not-the-right-one");
		MvcResult unknownAccount = signInAs("personne@example.com", SecurityFixtures.PASSWORD);

		assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
		assertThat(unknownAccount.getResponse().getStatus()).isEqualTo(401);
		// Same body down to the character, once the per-request traceId is removed.
		assertThat(withoutTraceId(unknownAccount)).isEqualTo(withoutTraceId(wrongPassword));
	}

	// CSRF is on, and it is not decorative: a POST without the token is refused. Turning it off "to
	// simplify" would undo the very reason an opaque session cookie was chosen.
	@Test
	void aPostWithNoCsrfTokenIsRefused() throws Exception {
		MvcResult noToken = this.mvc
			.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(signInBody(SecurityFixtures.ALICE.email(), SecurityFixtures.PASSWORD)))
			.andReturn();
		assertThat(noToken.getResponse().getStatus()).isEqualTo(403);
	}

	// Session fixation (guide 5.3). The sign-in is a controller, so the chain's changeSessionId()
	// setting does not run for it: the test signs in WITH an existing session cookie and demands a
	// different id afterwards, and that the planted one is worthless.
	@Test
	void signInRegeneratesTheSessionId() throws Exception {
		Cookie planted = sessionOf(signInAs(SecurityFixtures.ALICE.email(), SecurityFixtures.PASSWORD));

		MvcResult second = this.mvc
			.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(signInBody(SecurityFixtures.BOB.email(), SecurityFixtures.PASSWORD))
				.cookie(planted)
				.with(csrf()))
			.andReturn();
		assertThat(second.getResponse().getStatus()).isEqualTo(200);
		Cookie fresh = sessionOf(second);

		assertThat(fresh.getValue()).as("the same id before and after sign-in would mean fixation is possible")
			.isNotEqualTo(planted.getValue());
		assertThat(this.mvc.perform(get("/api/v1/me").cookie(planted)).andReturn().getResponse().getStatus())
			.as("the planted id is now worthless")
			.isEqualTo(401);
		assertThat(this.mvc.perform(get("/api/v1/me").cookie(fresh)).andReturn().getResponse().getStatus())
			.isEqualTo(200);
	}

	private static Cookie sessionOf(MvcResult result) {
		return java.util.Arrays.stream(result.getResponse().getCookies())
			.filter(cookie -> !"XSRF-TOKEN".equals(cookie.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no session cookie in the response"));
	}

	private MvcResult signInAs(String email, String password) throws Exception {
		return this.mvc
			.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(signInBody(email, password))
				.with(csrf()))
			.andReturn();
	}

	private String signInBody(String email, String password) {
		return this.json.writeValueAsString(Map.of("email", email, "password", password));
	}

	/**
	 * The session travels in a cookie, which is the only place it ever travels: Spring Session
	 * stores it in Redis and hands back a key. Replaying the cookie is therefore also the
	 * cheapest proof that the mechanism is the one SEC-01 chose — an opaque reference, not a
	 * self-contained token.
	 */
	/** The trace id differs per request by design; everything else must not. */
	private static String withoutTraceId(MvcResult result) throws java.io.UnsupportedEncodingException {
		return result.getResponse().getContentAsString().replaceAll(",?\"traceId\":\"[0-9a-f]+\"", "");
	}

	private static Cookie[] cookies(MvcResult result) {
		return result.getResponse().getCookies();
	}

}
