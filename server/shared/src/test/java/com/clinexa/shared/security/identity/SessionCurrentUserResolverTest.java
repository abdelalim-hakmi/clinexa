package com.clinexa.shared.security.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.clinexa.shared.security.assignment.AssignmentsProvider;
import com.clinexa.shared.security.assignment.AssignmentsUnavailableException;

/**
 * The identity contract, checked where it is cheapest to get wrong.
 */
class SessionCurrentUserResolverTest {

	private static final UUID ACCOUNT = UUID.fromString("0193a000-0001-7000-8000-0000000000a1");

	private static final UUID CLINIC = UUID.fromString("0193a000-0000-7000-8000-00000000000a");

	private final AtomicInteger calls = new AtomicInteger();

	private final AssignmentsProvider provider = accountId -> {
		this.calls.incrementAndGet();
		return Map.of(CLINIC, Set.of(ClinicRole.PRACTITIONER));
	};

	private final SessionCurrentUserResolver resolver = new SessionCurrentUserResolver(this.provider);

	@BeforeEach
	void openARequest() {
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
	}

	@AfterEach
	void close() {
		RequestContextHolder.resetRequestAttributes();
		SecurityContextHolder.clearContext();
	}

	@Test
	void withNoAuthenticationThereIsNobody() {
		assertThat(this.resolver.resolve()).isEmpty();
	}

	// INV-6 in spirit: only this class reads the SecurityContext, and it only accepts our principal.
	@Test
	void aForeignPrincipalIsNotAnIdentity() {
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("anonymous", null, Set.of()));
		assertThat(this.resolver.resolve()).isEmpty();
	}

	// Guide 4.4: memberships are read per request — never carried by the principal...
	@Test
	void assignmentsAreReadOnDemand() {
		authenticate();
		assertThat(this.calls).hasValue(0);

		CurrentUser user = this.resolver.resolve().orElseThrow();
		assertThat(user.accountId()).isEqualTo(ACCOUNT);
		assertThat(user.subject()).isEqualTo("alice@clinic-a.ma");
		assertThat(user.globalRoles()).isEmpty();
		// Still nothing loaded: reading the identity must not cost a call to identity-service.
		assertThat(this.calls).hasValue(0);

		assertThat(user.rolesIn(CLINIC)).containsExactly(ClinicRole.PRACTITIONER);
		assertThat(user.isAssignedTo(CLINIC)).isTrue();
		assertThat(user.isAssignedTo(UUID.randomUUID())).isFalse();
		assertThat(this.calls).hasValue(1);
	}

	// ... and cached for the request only: twice in one request is one call, a new request is a new one.
	@Test
	void theCacheLastsOnlyForTheRequest() {
		authenticate();
		this.resolver.resolve().orElseThrow().assignments();
		this.resolver.resolve().orElseThrow().assignments();
		assertThat(this.calls).hasValue(1);

		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
		this.resolver.resolve().orElseThrow().assignments();
		assertThat(this.calls).hasValue(2);
	}

	// Fail-closed (guide 8.3): an unreachable source refuses, it never yields an empty map —
	// an empty map would read as "member of nothing", which is a 403 too, but by accident.
	@Test
	void anUnreachableSourceRefuses() {
		authenticate();
		SessionCurrentUserResolver resolver = new SessionCurrentUserResolver(accountId -> {
			throw new AssignmentsUnavailableException(new IllegalStateException("identity-service down"));
		});
		CurrentUser user = resolver.resolve().orElseThrow();
		assertThatThrownBy(user::assignments).isInstanceOf(AssignmentsUnavailableException.class);
	}

	// The session is stored in Redis and read back by another service: the principal must survive
	// serialization, and must not drag the password hash along with it.
	@Test
	void thePrincipalIsSerializableAndSecretFree() throws Exception {
		AuthenticatedAccount principal = new AuthenticatedAccount(ACCOUNT, "alice@clinic-a.ma", "$2a$10$hash", true);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(principal);
		}
		AuthenticatedAccount reread;
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			reread = (AuthenticatedAccount) in.readObject();
		}

		assertThat(reread.accountId()).isEqualTo(ACCOUNT);
		assertThat(reread.getUsername()).isEqualTo("alice@clinic-a.ma");
		assertThat(reread.isEnabled()).isTrue();
		assertThat(reread.getPassword()).as("the hash must not survive into Redis").isNull();
		assertThat(reread.getAuthorities()).as("a role belongs to the pair (account, clinic), not to the session")
			.isEmpty();
	}

	private void authenticate() {
		AuthenticatedAccount principal = new AuthenticatedAccount(ACCOUNT, "alice@clinic-a.ma", null, true);
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, Set.of()));
	}

}
