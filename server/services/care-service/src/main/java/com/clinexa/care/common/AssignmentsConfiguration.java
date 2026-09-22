package com.clinexa.care.common;

import java.time.Duration;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.clinexa.shared.security.assignment.AssignmentsHttpProvider;
import com.clinexa.shared.security.assignment.AssignmentsProvider;

/**
 * {@code SEC-10}, consumer side: {@code care-service} owns no identity data, so it asks
 * {@code identity-service} which clinics the current account belongs to.
 * <p>
 * <strong>The two rejected options do not come back here.</strong> Not a header set by the gateway —
 * the gateway is a router, not a trust perimeter, and this service must stay safe when reached
 * directly. Not the memberships carried in the session — that would postpone every revocation to the
 * next login (guide 4.4, 8.4).
 * <p>
 * <strong>The timeouts are the point, not a detail.</strong> Every tenant route of this service now
 * waits on this call, so the failure has to be fast: a short connect and read timeout turns an
 * unreachable {@code identity-service} into an immediate {@code 403} instead of a stalled request
 * pile-up. Refusing is the intended behaviour (criterion 12) — serving unfiltered would be the bug.
 * <p>
 * The builder is {@code @LoadBalanced}, so the URI names the service and never a host and a port:
 * {@code identity-service} runs with at least two instances from V0 (guide 8.6), precisely because
 * its availability now conditions this one.
 */
@Configuration(proxyBeanMethods = false)
class AssignmentsConfiguration {

	/**
	 * The plain builder, and the one anything else in the context gets.
	 * <p>
	 * <strong>It is {@code @Primary} for a reason that costs an afternoon to find.</strong> The
	 * Eureka client itself asks the context for a {@code RestClient.Builder} to call the discovery
	 * server with. If the only builder in the context is the load-balanced one below, Eureka's own
	 * call to {@code http://localhost:8761/eureka} goes through the load balancer, which tries to
	 * resolve {@code localhost} as a service name and fails with "No instances available for
	 * localhost" — the service stays up, answers {@code /actuator/health}, and never registers.
	 */
	@Bean
	@Primary
	RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}

	/** The load-balanced one, used for the {@code SEC-10} call and nothing else. */
	@Bean
	@LoadBalanced
	RestClient.Builder loadBalancedRestClientBuilder() {
		return RestClient.builder();
	}

	@Bean
	AssignmentsProvider assignmentsProvider(@LoadBalanced RestClient.Builder builder,
			// Property keys match the Config Server file
			// configurations/care-service.yml: clinexa.security.identity-uri / assignments-timeout.
			@Value("${clinexa.security.identity-uri:lb://identity-service}") String identityUri,
			@Value("${clinexa.security.assignments-timeout:2s}") Duration timeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(timeout).build());
		factory.setReadTimeout(timeout);
		return new AssignmentsHttpProvider(builder.requestFactory(factory).build(), identityUri);
	}

}
