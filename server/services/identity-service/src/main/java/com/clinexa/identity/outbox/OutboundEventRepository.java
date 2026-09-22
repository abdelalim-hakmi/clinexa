package com.clinexa.identity.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The outbox table — and the one place in {@code identity-service} with a declared exemption to I9.
 * <p>
 * <strong>Why an exemption is needed.</strong> {@link OutboundEvent} carries {@code @TenantId},
 * because it is written inside a request that always has a clinic. The <em>relay</em> that
 * publishes those rows has no request and no clinic: it drains every tenant's events. Under the
 * tenant filter it would read nothing at all, which is the fail-closed of L2 working exactly as
 * designed — and useless here.
 * <p>
 * <strong>Why the exemption is safe.</strong> Guide 6.3 allows a derogation provided it is
 * nominative, written in the file, and covered by a cross test. All three hold: it is these two
 * methods and no others, {@link TenantOverride} marks them so INV-3 can tell them from an
 * accidental native query, and {@code OutboxRelayTest} checks the relay publishes the events of
 * both clinics and that nothing else in the service can read across tenants.
 * <p>
 * {@code FOR UPDATE SKIP LOCKED} is what lets several instances drain the same outbox without
 * publishing anything twice — and {@code identity-service} runs at least two instances from V0
 * (guide 8.6).
 */
public interface OutboundEventRepository extends JpaRepository<OutboundEvent, UUID> {

	/**
	 * Claims a batch of unpublished events, across every clinic.
	 * <p>
	 * Native, with no tenant filter — see the class javadoc. It is the only read in this service
	 * that deliberately crosses clinics, and it returns opaque envelopes to a relay that does
	 * nothing but forward them.
	 */
	@TenantOverride("The outbox relay has no request, therefore no clinic: it drains the events of every tenant.")
	@Query(value = """
			SELECT * FROM outbound_event
			WHERE published_at IS NULL
			ORDER BY created_at
			LIMIT :size
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboundEvent> claimToPublish(@Param("size") int size);

	/**
	 * Marks a batch as published.
	 * <p>
	 * Same exemption, same reason: the rows belong to several clinics, and the relay has none.
	 */
	@TenantOverride("Counterpart of claimToPublish: marks events of several clinics as published.")
	@Modifying
	@Query(value = "UPDATE outbound_event SET published_at = :when WHERE id IN (:ids)", nativeQuery = true)
	int markPublished(@Param("ids") List<UUID> ids, @Param("when") Instant when);

}
