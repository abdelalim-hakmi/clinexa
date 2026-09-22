package com.clinexa.shared.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The fail-closed behaviour of the tenant context, tested on its own — every service inherits it.
 */
class TenantContextTest {

	private final TenantContext tenantContext = new TenantContext();

	@AfterEach
	void cleanUp() {
		this.tenantContext.clear();
	}

	// I8: no context on a tenant operation is a refusal, never a neutral value.
	@Test
	void withNoContextTheRequestIsRefused() {
		assertThat(this.tenantContext.clinicId()).isEmpty();
		assertThatThrownBy(this.tenantContext::requireClinicId).isInstanceOf(SecurityDenial.class)
			.extracting(e -> ((SecurityDenial) e).code())
			.isEqualTo(SecurityErrorCode.AUTH_CLINIC_CONTEXT_MISSING);
	}

	// The sentinel is not a usable tenant either: it is the ORM's "no clinic", not a clinic.
	@Test
	void theTenantSentinelIsNotAValidClinic() {
		this.tenantContext.set(TenantContext.NONE);
		assertThatThrownBy(this.tenantContext::requireClinicId).isInstanceOf(SecurityDenial.class);
	}

	@Test
	void thePositionedClinicIsReturned() {
		UUID clinic = UUID.randomUUID();
		this.tenantContext.set(clinic);
		assertThat(this.tenantContext.requireClinicId()).isEqualTo(clinic);
	}

	// I7: the context does not cross a thread on its own. A consumer or an @Async task starts blank.
	@Test
	void theContextDoesNotCrossAThread() throws Exception {
		this.tenantContext.set(UUID.randomUUID());
		assertThat(CompletableFuture.supplyAsync(() -> this.tenantContext.clinicId()).get()).isEmpty();
	}

	// ... which is why crossing one must be explicit — and must carry a clinic.
	@Test
	void runInRepositionsAndRestores() {
		UUID origin = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		this.tenantContext.set(origin);

		UUID seenInside = this.tenantContext.runIn(other, this.tenantContext::requireClinicId);

		assertThat(seenInside).isEqualTo(other);
		assertThat(this.tenantContext.requireClinicId()).isEqualTo(origin);
	}

	@Test
	void runInRefusesAMissingClinic() {
		assertThatThrownBy(() -> this.tenantContext.runIn(null, () -> "written"))
			.isInstanceOf(SecurityDenial.class);
		assertThatThrownBy(() -> this.tenantContext.runIn(TenantContext.NONE, () -> "written"))
			.isInstanceOf(SecurityDenial.class);
	}

	@Test
	void runInRestoresEvenAfterAnError() {
		UUID origin = UUID.randomUUID();
		this.tenantContext.set(origin);
		assertThatThrownBy(() -> this.tenantContext.runIn(UUID.randomUUID(), () -> {
			throw new IllegalStateException("boom");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(this.tenantContext.requireClinicId()).isEqualTo(origin);
	}

	// L2 never says "no filter": without a context it answers a tenant no clinic can have.
	@Test
	void theResolverReturnsTheSentinelOutsideAContext() {
		TenantIdentifierResolverBase resolver = new TenantIdentifierResolverBase(this.tenantContext);
		assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(TenantContext.NONE);
		assertThat(resolver.isRoot(TenantContext.NONE)).isFalse();

		UUID clinic = UUID.randomUUID();
		this.tenantContext.set(clinic);
		assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(clinic);
		assertThat(resolver.isRoot(clinic)).isFalse();
	}

}
