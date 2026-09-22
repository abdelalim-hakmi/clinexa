package com.clinexa.care.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;

import com.clinexa.care.support.AssignmentsForTest;
import com.clinexa.care.support.CareIntegrationTest;
import com.clinexa.care.support.CareTestData;
import com.clinexa.care.support.SessionsCare;
import com.clinexa.shared.security.assignment.AssignmentsHttpProvider;
import com.clinexa.shared.security.assignment.AssignmentsUnavailableException;
import com.clinexa.shared.security.assignment.AssignmentsProvider;
import com.clinexa.shared.security.assignment.AssignmentsResponse;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.identity.ClinicRole;

import tools.jackson.databind.json.JsonMapper;

/**
 * <strong>Criterion 12</strong> — {@code identity-service} unreachable means {@code 403} on every
 * tenant route, never a quiet bypass of the filter.
 * <p>
 * This is one of the two criteria that cannot be seen while everything works: a fail-open is
 * invisible until something breaks, so something has to be broken on purpose. Here it is broken
 * twice, at two different levels, because the two failures are not the same bug:
 * <ul>
 * <li>the <strong>whole chain</strong>, with the source of memberships refusing to answer — what a
 * real outage looks like to a caller;</li>
 * <li>the <strong>real HTTP client</strong> ({@code SEC-10} / O3) against a real server that is
 * then shut down — which also proves, on the way, that the client speaks the wire contract of
 * {@code GET /internal/accounts/&#123;id&#125;/assignments} correctly.</li>
 * </ul>
 * The operational counterpart of this criterion is written in guide 8.6: from V0 on,
 * {@code identity-service} runs with at least two instances, because its availability now
 * conditions every tenant route of every service.
 */
@CareIntegrationTest
class AssignmentsUnavailableTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	SessionsCare sessions;

	@Autowired
	CareTestData data;

	@Autowired
	JsonMapper json;

	@BeforeEach
	void install() {
		this.data.install();
	}

	@AfterEach
	void restore() {
		AssignmentsForTest.unavailable = false;
	}

	@Test
	void criterion12AnUnreachableSourceRefusesTheTenantRoute() throws Exception {
		// Works first, so the refusal below cannot be blamed on anything else.
		assertThat(readClinical().getResponse().getStatus()).isEqualTo(200);

		AssignmentsForTest.unavailable = true;

		MvcResult duringTheOutage = readClinical();
		assertThat(duringTheOutage.getResponse().getStatus()).as("never a service with no tenant filter")
			.isEqualTo(403);
		assertThat(duringTheOutage.getResponse().getContentAsString()).contains("AUTH_ASSIGNMENTS_UNAVAILABLE")
			.doesNotContain(SecurityFixtures.P1_A.medicalHistory());
	}

	/**
	 * The real O3 client, against a real server: it reads the contract, and it fails closed when the
	 * server disappears. No mock — a mocked client proves the mock returns what it was told to.
	 */
	@Test
	void theSec10ClientReadsTheContractThenFailsClosed() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/accounts/", exchange -> {
			byte[] body = this.json
				.writeValueAsString(new AssignmentsResponse(SecurityFixtures.ERIN.id(),
						List.of(new AssignmentsResponse.ClinicAssignment(SecurityFixtures.CLINIC_A,
								Set.of(ClinicRole.PRACTITIONER)),
								new AssignmentsResponse.ClinicAssignment(SecurityFixtures.CLINIC_B,
										Set.of(ClinicRole.RECEPTIONIST)))))
				.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();

		try {
			AssignmentsProvider provider = provider("http://127.0.0.1:" + server.getAddress().getPort());
			Map<UUID, Set<ClinicRole>> assignments = provider.assignmentsOf(SecurityFixtures.ERIN.id());

			assertThat(assignments).containsOnlyKeys(SecurityFixtures.CLINIC_A, SecurityFixtures.CLINIC_B);
			assertThat(assignments.get(SecurityFixtures.CLINIC_A)).containsExactly(ClinicRole.PRACTITIONER);
			assertThat(assignments.get(SecurityFixtures.CLINIC_B)).containsExactly(ClinicRole.RECEPTIONIST);

			// Now cut the service, exactly as criterion 12 asks.
			server.stop(0);
			assertThatThrownBy(() -> provider.assignmentsOf(SecurityFixtures.ERIN.id()))
				.as("unreachable must become a refusal, never an empty set that would look like one")
				.isInstanceOf(AssignmentsUnavailableException.class);
		}
		finally {
			server.stop(0);
		}
	}

	private AssignmentsProvider provider(String uri) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
		factory.setReadTimeout(Duration.ofSeconds(1));
		return new AssignmentsHttpProvider(RestClient.builder().requestFactory(factory).build(), uri);
	}

	private MvcResult readClinical() throws IOException, Exception {
		return this.mvc
			.perform(get("/api/v1/clinics/{c}/records/{d}/clinical", SecurityFixtures.CLINIC_A,
					SecurityFixtures.P1_A.id())
				.cookie(this.sessions.login(SecurityFixtures.ALICE)))
			.andReturn();
	}

}
