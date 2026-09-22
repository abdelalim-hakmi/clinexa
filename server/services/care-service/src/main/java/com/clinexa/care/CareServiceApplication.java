package com.clinexa.care;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CMP-CARE — <strong>skeleton only</strong> ({@code SEC-14}).
 * <p>
 * It exists for one reason: the tenant-isolation and RBAC rules of the security foundation need a
 * real tenant service with real tenant entities and a real clinical route to be proved against, and
 * no business feature exists in V0. So the perimeter is closed by decision, not by convention:
 * <strong>two business tables, two reads, one access log, zero write route, zero UI, zero business
 * logic.</strong> Anything added before V1 requires an ADR.
 * <p>
 * It is also the second consumer of {@code SEC-10}: it owns no identity data, so it asks
 * {@code identity-service} for the memberships of the current account, and refuses when it cannot
 * (criterion 12).
 */
@SpringBootApplication
public class CareServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CareServiceApplication.class, args);
	}

}
