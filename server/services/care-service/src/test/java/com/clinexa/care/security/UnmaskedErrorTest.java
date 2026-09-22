package com.clinexa.care.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.clinexa.care.support.AssignmentsForTest;
import com.clinexa.care.support.CareContainers;
import com.clinexa.care.support.CareTestData;
import com.clinexa.care.support.SessionsCare;
import com.clinexa.shared.security.fixtures.SecurityFixtures;

import jakarta.servlet.http.Cookie;

/**
 * The container's error dispatch must not be swallowed by the chain's last line.
 * <p>
 * <strong>Why a real server and not {@code MockMvc}.</strong> {@code MockMvc} never forwards to
 * {@code /error}: it stops at the exception. The bug only exists once a servlet container performs
 * the ERROR dispatch, and there {@code anyRequest().denyAll()} refuses {@code /error} — so a plain
 * {@code 400} came back as a {@code 403 AUTH_ROLE_INSUFFICIENT}, a role refusal for a request that
 * had nothing to do with roles. That defeats the point of the three distinguishable refusals: a
 * malformed request, or a genuine {@code 500}, would be read as an authorization problem.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.cloud.config.enabled=false", "eureka.client.enabled=false",
		"spring.config.import=file:../../platform/config-server/src/main/resources/configurations/care-service.yml" })
@Import({ CareContainers.class, AssignmentsForTest.class })
class UnmaskedErrorTest {

	@Value("${local.server.port}")
	int port;

	@Autowired
	SessionsCare sessions;

	@Autowired
	CareTestData data;

	@BeforeEach
	void install() {
		this.data.install();
	}

	@Test
	void aMalformedIdentifierAnswers400AndNotARoleRefusal() throws Exception {
		HttpResponse<String> response = read(
				"/api/v1/clinics/" + SecurityFixtures.CLINIC_A + "/records/not-a-uuid/administrative");

		assertThat(response.statusCode()).as("body: %s", response.body()).isEqualTo(400);
		assertThat(response.body()).doesNotContain("AUTH_ROLE_INSUFFICIENT");
	}

	// The fix opens only the ERROR dispatch. A request for /error itself is still an ordinary request,
	// and the last line of the chain still refuses it.
	@Test
	void aDirectRequestTo_error_staysRefused() throws Exception {
		assertThat(read("/error").statusCode()).isIn(401, 403);
	}

	private HttpResponse<String> read(String path) throws Exception {
		Cookie session = this.sessions.login(SecurityFixtures.ALICE);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Cookie", session.getName() + "=" + session.getValue())
			.header("Accept", "application/json")
			.GET()
			.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

}
