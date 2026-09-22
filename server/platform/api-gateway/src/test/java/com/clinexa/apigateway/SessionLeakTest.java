package com.clinexa.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.web.client.RestClient;

/**
 * The gateway must never carry a cookie from one caller to the next.
 * <p>
 * <strong>This test exists because it failed.</strong> Apache HttpClient 5 — the implementation
 * Boot selects when it is on the classpath, and it is — enables cookie management by default, with
 * <em>one cookie store shared by the whole client</em>. A gateway proxies every user through one
 * client, so it kept the session cookie of whoever signed in last and replayed it upstream for
 * everyone: an anonymous {@code GET /api/v1/me} came back as another person, with their clinics
 * and their roles.
 * <p>
 * Nothing failed, nothing logged, every page rendered. That is why the rule is pinned here rather
 * than left to the absence of a dependency: the day something pulls {@code httpclient5} back in, or
 * someone drops {@code StatelessPassthroughConfiguration} as "useless", this goes red.
 * <p>
 * It runs against a tiny local server rather than the whole platform: the defect is in the HTTP
 * client's configuration, so that is what the test drives — two sequential calls, and the second
 * must carry no cookie of the first.
 */
class SessionLeakTest {

	private static HttpServer server;

	private static final List<String> receivedCookies = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void startUpstreamServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			List<String> headers = exchange.getRequestHeaders().get("Cookie");
			receivedCookies.add(headers == null ? "" : String.join("; ", headers));
			// Answers like a sign-in would: it hands out a session cookie.
			exchange.getResponseHeaders().add("Set-Cookie", "CLINEXA_SESSION=dan-session; Path=/; HttpOnly");
			byte[] body = "{\"email\":\"dan@clinic-b.ma\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopUpstreamServer() {
		server.stop(0);
	}

	@Test
	void gatewayClientNeverReplaysAReceivedCookie() {
		receivedCookies.clear();
		RestClient client = RestClient.builder()
			.requestFactory(new StatelessPassthroughConfiguration().clientHttpRequestFactoryBuilder().build())
			.build();
		String base = "http://127.0.0.1:" + server.getAddress().getPort();

		// 1) Somebody signs in: the upstream hands back a session cookie.
		client.get().uri(base + "/api/v1/auth/login").retrieve().body(String.class);
		// 2) Somebody else, with no cookie of their own, calls through the same gateway.
		client.get().uri(base + "/api/v1/me").retrieve().body(String.class);

		assertThat(receivedCookies).hasSize(2);
		assertThat(receivedCookies.get(1))
			.as("the gateway replayed the first caller's session: everyone becomes them")
			.doesNotContain("CLINEXA_SESSION");
	}

	/** The same scenario with the default client, to show the test is not vacuous. */
	@Test
	void defaultClientWouldReplayTheCookie() {
		receivedCookies.clear();
		RestClient client = RestClient.builder()
			.requestFactory(ClientHttpRequestFactoryBuilder.httpComponents().build())
			.build();
		String base = "http://127.0.0.1:" + server.getAddress().getPort();

		client.get().uri(base + "/api/v1/auth/login").retrieve().body(String.class);
		client.get().uri(base + "/api/v1/me").retrieve().body(String.class);

		List<String> seen = new ArrayList<>(receivedCookies);
		assertThat(seen).hasSize(2);
		assertThat(seen.get(1))
			.as("if this no longer contains the cookie, Apache's cookie management has changed"
					+ " and the comment on StatelessPassthroughConfiguration needs a second look")
			.contains("CLINEXA_SESSION");
	}

}
