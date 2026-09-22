package com.clinexa.identity.outbox;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a query that deliberately escapes the tenant filter, and says why.
 * <p>
 * I9 forbids native SQL and bulk JPQL on a tenant table because {@code @TenantId} does not filter
 * them — so such a query reads every clinic. Guide 6.3 allows a derogation on three conditions,
 * and this annotation is how all three are met at once: it is <strong>nominative</strong> (it sits
 * on the one method concerned), it is <strong>written down</strong> (the reason is required), and
 * INV-3 can tell a declared derogation from a query someone added without thinking — an
 * undeclared native query fails the build.
 * <p>
 * Adding it to silence INV-3 is the failure mode to watch for in review. The reason text is what
 * makes that visible: a derogation whose justification does not survive being read out loud is not
 * one.
 */
@Documented
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantOverride {

	/** Why this query may cross clinics. Reviewed like any other security decision. */
	String value();

}
