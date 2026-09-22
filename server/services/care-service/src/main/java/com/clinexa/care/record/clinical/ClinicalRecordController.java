package com.clinexa.care.record.clinical;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.clinexa.care.accesslog.LogAccess;

/**
 * The clinical read of a patient record — {@code PRACTITIONER} only, including in the face of
 * {@code CLINIC_ADMIN}.
 * <p>
 * That last point is the one people try to "simplify": the administrator of a clinic manages
 * members, settings and billing, and has no access to the schedule or to a patient record. Roles are
 * disjoint, not nested (I6). A manager who also works the front desk holds a second membership — a
 * stacking of memberships, written in the database and revocable on its own — never a role that
 * swallows the others.
 * <p>
 * {@code SEC-03} shares the record between every practitioner of the clinic; {@link LogAccess} is
 * the counterpart of that decision. Since compartmentalisation does not exist here, every read has
 * to be explainable afterwards.
 */
@RestController
@RequestMapping("/api/v1/clinics/{clinicId}/records/{recordId}")
class ClinicalRecordController {

	private final ClinicalRecordRepository clinicalRecords;

	ClinicalRecordController(ClinicalRecordRepository clinicalRecords) {
		this.clinicalRecords = clinicalRecords;
	}

	@GetMapping("/clinical")
	@LogAccess(resource = "clinical_record", action = "READ")
	ResponseEntity<ClinicalRecordDto> read(@PathVariable UUID clinicId, @PathVariable UUID recordId) {
		return this.clinicalRecords.findById(recordId)
			.map(ClinicalRecordDto::from)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

}
