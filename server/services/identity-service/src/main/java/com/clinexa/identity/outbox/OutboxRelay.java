package com.clinexa.identity.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the outbox rows to Kafka and marks them sent — the second half of the transactional
 * outbox, the half that has no tenant.
 * <p>
 * It runs outside any request, so {@code TenantContext} is empty and the ORM filter would hide
 * every row. That is why it goes through the two declared derogations of
 * {@link OutboundEventRepository} rather than through the entity: the exemption is nominative,
 * written down and cross-tested, exactly as guide 6.3 requires.
 * <p>
 * It forwards <strong>opaque envelopes</strong>. It never parses the payload, never re-reads a
 * business table, and therefore never needs a tenant of its own — which is what keeps the
 * derogation as narrow as it is.
 * <p>
 * {@code FOR UPDATE SKIP LOCKED} in the claim query is what makes several instances safe: two
 * relays draining the same table each take a different batch instead of publishing the same event
 * twice. {@code identity-service} runs at least two instances from V0 (guide 8.6), so this is not a
 * theoretical case.
 * <p>
 * Publication is at-least-once by construction: a crash between the send and the mark re-sends the
 * event. Consumers key on {@code eventId} for that reason — none exists yet, and the events are
 * published anyway (guide 8.5).
 */
@Component
@ConditionalOnProperty(name = "clinexa.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboundEventRepository outbox;

	private final KafkaTemplate<String, String> kafka;

	private final int batchSize;

	private final Duration timeout;

	public OutboxRelay(OutboundEventRepository outbox, KafkaTemplate<String, String> kafka,
			@Value("${clinexa.outbox.relay.batch-size:100}") int batchSize,
			@Value("${clinexa.outbox.relay.publish-timeout:10s}") Duration timeout) {
		this.outbox = outbox;
		this.kafka = kafka;
		this.batchSize = batchSize;
		this.timeout = timeout;
	}

	@Scheduled(fixedDelayString = "${clinexa.outbox.relay.period:PT2S}")
	@Transactional
	public void drain() {
		List<OutboundEvent> batch = this.outbox.claimToPublish(this.batchSize);
		if (batch.isEmpty()) {
			return;
		}
		List<UUID> published = new ArrayList<>(batch.size());
		for (OutboundEvent event : batch) {
			try {
				// Waits for the broker's acknowledgement before marking the row. Firing and
				// forgetting would let a broker outage mark events as published and lose them for
				// good — the outbox exists precisely so that cannot happen.
				this.kafka.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
					.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
				published.add(event.getId());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			catch (ExecutionException | TimeoutException e) {
				// The row stays unpublished and the next pass picks it up. Stopping here keeps the
				// batch in order, which is what the clinic_id partition key exists to preserve.
				log.warn("Outbox relay: could not publish {} ({}), retrying next pass", event.getId(),
						e.toString());
				break;
			}
		}
		if (!published.isEmpty()) {
			this.outbox.markPublished(published, Instant.now());
			log.debug("Outbox relay: published {} event(s)", published.size());
		}
	}

}
