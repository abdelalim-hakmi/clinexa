package com.clinexa.care.record.clinical;

import java.util.UUID;

/**
 * What {@code GET .../records/{id}/clinical} returns — {@code PRACTITIONER} only.
 * <p>
 * A separate type from {@code AdministrativeRecordDto}, with <strong>no common parent</strong>: a
 * shared superclass would be the shortest path back to one DTO with runtime-masked fields, which is
 * exactly what I5 rejects. Two routes, two DTOs, two rules — and F5 asserts on the serialized JSON
 * rather than on the class, because the JSON is what leaves the server.
 */
public record ClinicalRecordDto(UUID recordId, String medicalHistory, String surgicalHistory,
		String familyHistory) {

	public static ClinicalRecordDto from(ClinicalRecord record) {
		return new ClinicalRecordDto(record.getRecordId(), record.getMedicalHistory(), record.getSurgicalHistory(),
				record.getFamilyHistory());
	}

}
