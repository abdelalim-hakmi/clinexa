package com.clinexa.care.record;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads of {@link PatientRecord}. Every one of them is filtered by {@code @TenantId} (L2), which is
 * why a plain {@code findById} is safe <em>here</em> and not on {@code Member}: this entity carries
 * the annotation, so Hibernate adds {@code clinic_id = ?} to the query itself.
 * <p>
 * That is also what produces the second refusal of criterion 5: a legitimate path with an id
 * belonging to another clinic finds nothing, so the answer is {@code 404} — which reveals nothing —
 * rather than {@code 403}, which would confirm that the id exists somewhere.
 * <p>
 * No native query and no bulk JPQL here (I9): {@code @TenantId} does not filter native SQL, so such
 * a query would read every clinic. INV-3 enforces it.
 */
public interface PatientRecordRepository extends JpaRepository<PatientRecord, UUID> {

	Optional<PatientRecord> findByIdAndStatus(UUID id, PatientRecordStatus status);

}
