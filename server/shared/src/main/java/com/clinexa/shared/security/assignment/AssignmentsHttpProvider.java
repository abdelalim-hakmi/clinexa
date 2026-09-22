package com.clinexa.shared.security.assignment;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.clinexa.shared.security.identity.ClinicRole;

/**
 * {@code SEC-10}, option O3: ask {@code identity-service} synchronously, over the internal API.
 * <p>
 * <strong>What this class must not become.</strong> Two options were rejected before it and neither
 * may creep back in (guide 8.4): a header set by the gateway — the gateway is a router, not a trust
 * perimeter, and a service must stay safe when reached directly — and the memberships carried in the
 * principal, which would postpone every revocation to the next login.
 * <p>
 * <strong>Caching.</strong> None here, deliberately. The one legitimate cache is per request, and it
 * belongs to {@code SessionCurrentUserResolver} which memoizes the call for the request it serves.
 * A cache with a longer life would be option O1 under another name.
 * <p>
 * <strong>Failure.</strong> Any failure becomes {@link AssignmentsUnavailableException}, which is
 * a {@code 403}. The timeouts that make that failure fast rather than slow are set on the
 * {@code RestClient.Builder} the service injects — the builder is {@code @LoadBalanced}, so the URI
 * below names the service, never a host and never a port.
 */
public class AssignmentsHttpProvider implements AssignmentsProvider {

	/** Never routed by the gateway: internal network only, LLD 21 §8. */
	static final String PATH = "/internal/accounts/{accountId}/assignments";

	private static final Logger log = LoggerFactory.getLogger(AssignmentsHttpProvider.class);

	private final RestClient restClient;

	private final String serviceUri;

	/**
	 * @param restClient built from a {@code @LoadBalanced RestClient.Builder} — never a
	 * {@code new RestTemplate()}, so instances stay discoverable through Eureka (guide 8.6)
	 * @param serviceUri the logical service, e.g. {@code lb://identity-service}
	 */
	public AssignmentsHttpProvider(RestClient restClient, String serviceUri) {
		this.restClient = restClient;
		this.serviceUri = serviceUri;
	}

	@Override
	public Map<UUID, Set<ClinicRole>> assignmentsOf(UUID accountId) {
		try {
			AssignmentsResponse response = this.restClient.get()
				.uri(this.serviceUri + PATH, accountId)
				.retrieve()
				.body(AssignmentsResponse.class);
			if (response == null) {
				throw new IllegalStateException("Empty response from " + this.serviceUri + PATH);
			}
			return response.toMap();
		}
		catch (AssignmentsUnavailableException e) {
			throw e;
		}
		catch (RuntimeException e) {
			// Fail-closed: refuse the request rather than continue without a tenant filter.
			log.warn("Assignments unavailable for account {}: {}", accountId, e.toString());
			throw new AssignmentsUnavailableException(e);
		}
	}

}
