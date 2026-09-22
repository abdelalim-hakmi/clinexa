package com.clinexa.care.record;

/**
 * A patient record is never physically deleted (SRS-D-06): it is merged into another (RG-21) or
 * anonymized (LLD 20 §11.1). Only an {@code ACTIVE} record takes part in the RG-16 uniqueness.
 */
public enum PatientRecordStatus {
	ACTIVE,
	MERGED,
	ANONYMIZED
}
