package com.clinexa.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.clinexa.identity.support.TestData;
import com.clinexa.identity.support.Sessions;
import com.clinexa.identity.support.IdentityTestSupport;
import com.clinexa.shared.security.fixtures.AuthorizationMatrix;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.fixtures.SecurityFixtures.AccountFixture;
import com.clinexa.shared.security.identity.ClinicRole;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * <strong>F2 — Route authorization</strong> for {@code identity-service}: one execution per cell
 * of {@code matrice.csv}, ✅ <strong>and</strong> ❌ (criterion 2).
 * <p>
 * It is parameterised from the matrix rather than written endpoint by endpoint, because a
 * hand-written suite forgets cells — and the ones it forgets are the {@code ❌}, which are the ones
 * that prove something. Checking that an administrator can list members says nothing; checking that
 * a practitioner cannot is the assertion that matters.
 * <p>
 * Every call is made in clinic A, by an account that is a member of clinic A. So a {@code 403} here
 * can only come from the role rule — the tenant is never the reason. Cross-tenant refusals are the
 * subject of {@code MemberSec13Test} and of the care service's isolation tests.
 */
@IdentityTestSupport
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityAuthorizationMatrixTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	Sessions sessions;

	@Autowired
	TestData data;

	@BeforeEach
	void install() {
		this.sessions = new Sessions(this.mvc, this.json);
		this.data.install();
	}

	static java.util.List<AuthorizationMatrix.Cell> matrix() {
		return AuthorizationMatrix.cells();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("matrix")
	void everyCellOfTheMatrix(AuthorizationMatrix.Cell cell) throws Exception {
		MvcResult result = this.mvc.perform(request(cell)).andReturn();
		int status = result.getResponse().getStatus();

		if (cell.allowed()) {
			// "Allowed" means "not refused by the route filter". The response may legitimately be a
			// 404 (an id that does not exist) or a 400 (a body we did not bother to make valid) —
			// what it may never be is 401 or 403.
			assertThat(status).as("%s should pass the route filter", cell).isNotIn(401, 403);
		}
		else if (cell.anonymous()) {
			// Not authenticated is 401, never 403: saying "you may not" to someone who has not said
			// who they are confuses the two refusals the whole foundation keeps apart.
			assertThat(status).as("%s should be refused for lack of authentication", cell).isEqualTo(401);
		}
		else {
			assertThat(status).as("%s should be refused for lack of role", cell).isEqualTo(403);
			assertThat(result.getResponse().getContentAsString()).contains("AUTH_ROLE_INSUFFICIENT");
		}
	}

	private MockHttpServletRequestBuilder request(AuthorizationMatrix.Cell cell) {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders
			.request(HttpMethod.valueOf(cell.verb()), path(cell.route()));
		if (!cell.anonymous()) {
			request.cookie(cookies(cell.role()));
		}
		if ("POST".equals(cell.verb())) {
			request.with(csrf());
		}
		if ("/api/v1/auth/login".equals(cell.route())) {
			// The one public route with a body: an empty one would be a 400 before the chain even
			// looks at authorization, which would make the ✅ assertion vacuous.
			request.contentType(MediaType.APPLICATION_JSON)
				.content(this.json.writeValueAsString(Map.of("email", SecurityFixtures.ALICE.email(), "password",
						SecurityFixtures.PASSWORD)));
		}
		return request;
	}

	/** Fills the {@code {}} placeholders of the matrix with fixtures of clinic A. */
	private static String path(String route) {
		String path = route.replace("/api/v1/clinics/{}", "/api/v1/clinics/" + SecurityFixtures.CLINIC_A);
		path = path.replace("/members/{}",
				"/members/" + SecurityFixtures.CAROL.memberId(SecurityFixtures.CLINIC_A, ClinicRole.CLINIC_ADMIN));
		path = path.replace("/internal/accounts/{}", "/internal/accounts/" + SecurityFixtures.ALICE.id());
		if (path.contains("{}")) {
			throw new IllegalStateException("Unsubstituted variable in route " + route);
		}
		return path;
	}

	private Cookie[] cookies(String role) {
		return this.sessions.login(account(role));
	}

	/** The fixture that holds exactly this role in clinic A — no more, no less. */
	private static AccountFixture account(String role) {
		return switch (ClinicRole.valueOf(role)) {
			case RECEPTIONIST -> SecurityFixtures.BOB;
			case PRACTITIONER -> SecurityFixtures.ALICE;
			case CLINIC_ADMIN -> SecurityFixtures.CAROL;
		};
	}

}
