package com.clinexa.care.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.clinexa.care.support.CareIntegrationTest;
import com.clinexa.care.support.CareTestData;
import com.clinexa.care.support.SessionsCare;
import com.clinexa.shared.security.fixtures.AuthorizationMatrix;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.fixtures.SecurityFixtures.AccountFixture;
import com.clinexa.shared.security.identity.ClinicRole;

/**
 * <strong>F2 — Route authorization</strong> for {@code care-service}: one execution per cell of
 * {@code matrice.csv}, ✅ <strong>and</strong> ❌ (criterion 2), and criterion 8 with it.
 * <p>
 * The matrix of this service is four lines long and carries the central business rule of the whole
 * product: the clinical volet is {@code PRACTITIONER}, and {@code CLINIC_ADMIN} reaches neither it
 * nor the administrative volet. That second half is the one people try to "fix": roles are disjoint,
 * not nested (I6), and a manager who also works the front desk holds a second membership.
 * <p>
 * Every call is made in clinic A by an account of clinic A, so a {@code 403} here can only be a role
 * refusal — tenant refusals are {@code TenantIsolationTest}'s subject.
 */
@CareIntegrationTest
class CareAuthorizationMatrixTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	SessionsCare sessions;

	@Autowired
	CareTestData data;

	@BeforeEach
	void install() {
		this.data.install();
	}

	static java.util.List<AuthorizationMatrix.Cell> matrix() {
		return AuthorizationMatrix.cells();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("matrix")
	void eachCellOfTheMatrix(AuthorizationMatrix.Cell cell) throws Exception {
		MvcResult result = this.mvc.perform(request(cell)).andReturn();
		int status = result.getResponse().getStatus();

		if (cell.allowed()) {
			// "Allowed" means "not refused by the route filter" — a 404 would still be fine here.
			assertThat(status).as("%s should pass the route filter", cell).isNotIn(401, 403);
		}
		else if (cell.anonymous()) {
			assertThat(status).as("%s should be refused for lack of authentication", cell).isEqualTo(401);
		}
		else {
			assertThat(status).as("%s should be refused for lack of role", cell).isEqualTo(403);
			assertThat(result.getResponse().getContentAsString()).contains("AUTH_ROLE_INSUFFICIENT")
				.as("a refusal never leaks any clinical data")
				.doesNotContain(SecurityFixtures.P1_A.medicalHistory());
		}
	}

	private MockHttpServletRequestBuilder request(AuthorizationMatrix.Cell cell) {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders
			.request(HttpMethod.valueOf(cell.verb()), path(cell.route()));
		if (!cell.anonymous()) {
			request.cookie(this.sessions.login(account(cell.role())));
		}
		return request;
	}

	/** Fills the {@code {}} placeholders of the matrix with fixtures of clinic A. */
	private static String path(String route) {
		String path = route.replace("/api/v1/clinics/{}", "/api/v1/clinics/" + SecurityFixtures.CLINIC_A)
			.replace("/records/{}", "/records/" + SecurityFixtures.P1_A.id());
		if (path.contains("{}")) {
			throw new IllegalStateException("Unsubstituted variable in route " + route);
		}
		return path;
	}

	/** The fixture holding exactly this role in clinic A — no more, no less. */
	private static AccountFixture account(String role) {
		return switch (ClinicRole.valueOf(role)) {
			case RECEPTIONIST -> SecurityFixtures.BOB;
			case PRACTITIONER -> SecurityFixtures.ALICE;
			case CLINIC_ADMIN -> SecurityFixtures.CAROL;
		};
	}

}
