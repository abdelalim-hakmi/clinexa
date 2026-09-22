package com.clinexa.identity.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The envelope every Clinexa event carries (HLD 11 §2, LLD 21 §11).
 * <p>
 * {@code clinicId} is part of the envelope and not of the payload, and that is the point: a
 * consumer runs on its own thread, with no request and therefore <strong>no tenant context</strong>
 * (I7). It has to re-position the tenant explicitly before touching a repository, and the only
 * honest place to read it from is the envelope. An event without {@code clinicId} must make its
 * consumer fail — criterion 9 proves exactly that.
 * <p>
 * {@code eventVersion} is here from the first event rather than added at the first breaking change,
 * when adding it is no longer possible without one.
 *
 * @param eventId unique per event — what lets a consumer be idempotent
 * @param eventType the catalogue name, e.g. {@code identity.member.assigned}
 * @param eventVersion schema version of {@code payload}, starting at 1
 * @param occurredAt when the business fact happened, not when it was published
 * @param clinicId the tenant the fact belongs to — never null
 * @param traceId joins the event to the trace of the request that produced it
 * @param payload the facts themselves; never a password, never clinical content (LLD 21 §11.2)
 */
public record EventEnvelope(UUID eventId, String eventType, int eventVersion, Instant occurredAt, UUID clinicId,
		String traceId, Map<String, Object> payload) {

	public static final int CURRENT_VERSION = 1;

}
