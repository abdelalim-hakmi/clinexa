package com.clinexa.care.record.administrative;

import java.time.LocalDate;
import java.util.UUID;

import com.clinexa.care.record.PatientRecord;

/**
 * What {@code GET .../records/&#123;id&#125;/administrative} returns — identity and contact, and
 * nothing else.
 * <p>
 * <strong>One DTO per route rather than one DTO filtered at runtime</strong> (I5). The difference is
 * what happens when a field is added: on a single shared DTO a new field is exposed <em>by
 * default</em> and has to be remembered and masked; here it goes into one already-bounded DTO, and
 * putting it in the wrong one is a compilation-visible choice. It also turns an endless field-by-
 * field test into a route-by-route one, written once.
 * <p>
 * This record must never reference a type from {@code record.clinical} — INV-5 checks it at build
 * time, so the leak is caught before it can reach a response.
 */
public record AdministrativeRecordDto(UUID id, String lastName, String firstName, LocalDate dateOfBirth,
		String phone, String coverage, String status) {

	public static AdministrativeRecordDto from(PatientRecord record) {
		return new AdministrativeRecordDto(record.getId(), record.getLastName(), record.getFirstName(),
				record.getDateOfBirth(), record.getPhone(), record.getCoverage(), record.getStatus().name());
	}

}
