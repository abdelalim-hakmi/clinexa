package com.clinexa.apigateway;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the gateway's outgoing HTTP client <strong>stateless</strong>, which it is not by default.
 * <p>
 * <strong>The bug this closes.</strong> Apache HttpClient 5 — the implementation Spring Boot picks
 * when it is on the classpath — enables cookie management by default, backed by <em>one cookie
 * store shared by the whole client</em>. A gateway proxies every user through that single client,
 * so it collected the {@code CLINEXA_SESSION} cookie of whoever signed in last and replayed it
 * upstream on <em>every</em> subsequent request. An anonymous call to {@code GET /api/v1/me} then
 * answered with another person's identity.
 * <p>
 * It is the worst kind of defect: nothing fails, nothing logs, every page renders — it simply
 * serves the wrong person's data, and only under concurrency. {@code SessionLeakTest} reproduces
 * it, and would fail again if this configuration were removed.
 * <p>
 * <strong>Why here and not in the services.</strong> A gateway must carry no state about who is
 * calling: it forwards the cookie the client sent and keeps nothing. That is the same rule as
 * "the gateway is not a trust perimeter" — here applied to the connection rather than to
 * authorization. A service's own outgoing calls ({@code SEC-10}) are per-request and carry no user
 * cookie, so they are not exposed to this.
 * <p>
 * Removing the {@code httpclient5} dependency would also fix it today and silently break again the
 * day something pulls it back in. This states the requirement instead.
 */
@Configuration(proxyBeanMethods = false)
class StatelessPassthroughConfiguration {

	@Bean
	ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
		return ClientHttpRequestFactoryBuilder.httpComponents()
			.withHttpClientCustomizer(HttpClientBuilder::disableCookieManagement);
	}

}
