package com.clinexa.care.record.administrative;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.clinexa.care.record.PatientRecordRepository;

/**
 * The administrative read of a patient record — {@code RECEPTIONIST} and {@code PRACTITIONER}.
 * <p>
 * Three things are deliberately absent, and each is a rule rather than an omission:
 * <ul>
 * <li><strong>No {@code Authentication}, no {@code HttpSession}, no {@code Jwt} parameter.</strong>
 * A controller reads the identity through {@code CurrentUser} or not at all (INV-6). This one needs
 * none, because the clinic has already been validated by L1 and the row is already filtered by
 * {@code @TenantId}.</li>
 * <li><strong>No role check here.</strong> Route authorization is the matrix's job, declared once in
 * {@code CareSecurityChainConfiguration}; duplicating it in an annotation would let the two drift
 * apart.</li>
 * <li><strong>No write route.</strong> {@code SEC-14} bounds this service to two reads, and adding
 * one requires an ADR.</li>
 * </ul>
 * {@code clinicId} appears in the path and is <em>never</em> used to filter: it was validated by L1
 * and the filtering is Hibernate's. Reading it here would be trusting an identifier that came from
 * the request (I4).
 */
@RestController
@RequestMapping("/api/v1/clinics/{clinicId}/records/{recordId}")
class AdministrativeRecordController {

	private final PatientRecordRepository records;

	AdministrativeRecordController(PatientRecordRepository records) {
		this.records = records;
	}

	/**
	 * Criterion 5b: with a legitimate path but an id belonging to another clinic, the tenant filter
	 * finds nothing and the answer is {@code 404}. Answering {@code 403} would confirm that the id
	 * exists somewhere — the difference between "you may not come in here" and "there is nothing
	 * here for you".
	 */
	@GetMapping("/administrative")
	ResponseEntity<AdministrativeRecordDto> read(@PathVariable UUID clinicId, @PathVariable UUID recordId) {
		return this.records.findById(recordId)
			.map(AdministrativeRecordDto::from)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

}
