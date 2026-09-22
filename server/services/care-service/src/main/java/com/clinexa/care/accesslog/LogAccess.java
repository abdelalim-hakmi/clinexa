package com.clinexa.care.accesslog;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a read of clinical content, which {@link AccessLogAspect} records.
 * <p>
 * An annotation rather than a call inside each method, for one reason: added now it is an aspect
 * and a table, whereas added later it means instrumenting every clinical endpoint one at a time —
 * and missing one is invisible.
 *
 * @see AccessLogAspect
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogAccess {

	/** The table or aggregate being read, as it should read in the log — e.g. {@code clinical_record}. */
	String resource();

	/** What was done with it. Only {@code READ} exists at the foundation: there is no write route. */
	String action() default "READ";

	/**
	 * Name of the method parameter holding the identifier of the resource read.
	 * The default matches the two routes of the skeleton.
	 */
	String parameter() default "recordId";

}
