package com.clinexa.identity.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event into the transactional outbox — in the <strong>same transaction</strong> as the
 * business change that produced it (LLD 20 §7).
 * <p>
 * That is the whole reason the outbox exists rather than a direct call to a broker: publishing
 * outside the transaction gives two failure modes that are both wrong — the member is assigned and
 * the event is lost, or the event is out and the assignment rolled back. One transaction, one row,
 * and a relay that publishes it afterwards.
 * <p>
 * The three member events are published <strong>from V0, with no consumer at all</strong>
 * (guide 8.5). That looks like waste and is not: the marginal cost today is one insert, and it is
 * what makes the later switch from the synchronous {@code SEC-10} call (O3) to an event-fed local
 * replica (O4) a change of one implementation rather than a new integration.
 * <p>
 * The partition key is {@code clinic_id}, so every event of one clinic stays ordered relative to
 * the others.
 */
@Component
public class EventPublisher {

	private final OutboundEventRepository outbox;

	private final JsonMapper json;

	public EventPublisher(OutboundEventRepository outbox, JsonMapper json) {
		this.outbox = outbox;
		this.json = json;
	}

	/**
	 * @param type the catalogue name of the event, e.g. {@code identity.member.assigned}
	 * @param clinicId the tenant the fact belongs to — also the partition key
	 * @param facts the payload; never a credential, never clinical content (LLD 21 §11.2)
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public OutboundEvent publish(String type, UUID clinicId, Map<String, Object> facts) {
		EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), type, EventEnvelope.CURRENT_VERSION,
				Instant.now(), clinicId, MDC.get("traceId"), facts);
		return this.outbox.save(new OutboundEvent(type, clinicId.toString(), this.json.writeValueAsString(envelope),
				envelope.traceId()));
	}

}
