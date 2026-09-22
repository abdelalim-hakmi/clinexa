package com.clinexa.shared.security.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * The clinic the current operation belongs to — the single source the ORM filter and the
 * application guards both read.
 * <p>
 * <strong>Why a thread-local and not a request-scoped bean.</strong> Hibernate asks for the tenant
 * when a <em>session opens</em>, which also happens outside any HTTP request — Liquibase at
 * start-up, a scheduled job, a Kafka consumer. A request-scoped bean would throw there; a
 * thread-local simply has no value, which is exactly the answer those callers deserve.
 * <p>
 * <strong>It does not cross a thread on its own (I7).</strong> A consumer, an {@code @Async} method
 * or a scheduled job starts with nothing set: it must carry {@code clinicId} in its payload and
 * call {@link #runIn} explicitly, or fail. That is the whole point — a job that "works"
 * without a tenant is a job reading every clinic.
 * <p>
 * Whoever sets it clears it, always in a {@code finally} (guide 6.1): a pooled thread keeps its
 * thread-locals, so a leaked value would be inherited by the next, unrelated request.
 */
public class TenantContext {

	/**
	 * The tenant of a request that has none — fail-closed made concrete (I8).
	 * <p>
	 * It is a real UUID rather than {@code null} because {@code null} is what Hibernate reads as
	 * "no filter, return everything". No clinic may ever have this id: it is not a valid UUIDv7,
	 * and the database refuses it on every tenant table — through the foreign key to {@code clinic}
	 * in {@code identity-service}, and through a {@code CHECK (clinic_id <> '0000…')} in
	 * {@code care-service}, whose {@code clinic} table lives in another database. A write carrying
	 * it fails instead of landing in someone else's data (or in a phantom tenant's).
	 */
	public static final UUID NONE = new UUID(0L, 0L);

	private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

	/** The clinic in force, or empty — never a default value (that would be fail-open in disguise). */
	public Optional<UUID> clinicId() {
		return Optional.ofNullable(CURRENT.get());
	}

	/**
	 * The clinic in force, or a {@code 403}. The guard to call before any tenant-scoped work that
	 * is not already behind L1 — criterion 10: a tenant operation without context refuses, it never
	 * returns a complete result.
	 */
	public UUID requireClinicId() {
		UUID current = CURRENT.get();
		if (current == null || NONE.equals(current)) {
			throw new SecurityDenial(SecurityErrorCode.AUTH_CLINIC_CONTEXT_MISSING);
		}
		return current;
	}

	public void set(UUID clinicId) {
		CURRENT.set(clinicId);
	}

	public void clear() {
		CURRENT.remove();
	}

	/**
	 * Runs {@code work} in the given clinic and restores whatever was in force before.
	 * <p>
	 * This is the explicit re-positioning I7 demands of anything that runs outside a request: a
	 * consumer reads {@code clinicId} from the event envelope and wraps its handler in this call.
	 */
	public <T> T runIn(UUID clinicId, java.util.function.Supplier<T> work) {
		if (clinicId == null || NONE.equals(clinicId)) {
			throw new SecurityDenial(SecurityErrorCode.AUTH_CLINIC_CONTEXT_MISSING);
		}
		UUID previous = CURRENT.get();
		CURRENT.set(clinicId);
		try {
			return work.get();
		}
		finally {
			if (previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(previous);
			}
		}
	}

}
