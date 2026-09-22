package com.clinexa.care.record.clinical;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads of {@link ClinicalRecord}, filtered by {@code @TenantId} like every tenant repository.
 * No native query, no bulk JPQL (I9) — INV-3 enforces it.
 */
public interface ClinicalRecordRepository extends JpaRepository<ClinicalRecord, UUID> {

}
