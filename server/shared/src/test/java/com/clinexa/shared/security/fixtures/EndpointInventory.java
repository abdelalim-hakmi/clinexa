package com.clinexa.shared.security.fixtures;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Reads the routes a running service actually exposes, straight from its
 * {@code RequestMappingHandlerMapping} — the live counterpart to {@link AuthorizationMatrix}'s
 * declared ones, and what INV-1 compares against it in every {@code *InventoryTest}.
 * <p>
 * Written once here rather than once per service: the reflection and the path-variable
 * normalisation ({@code {id}} to {@code {}}, so a route compares equal regardless of what its
 * variables are named) is identical for every service and has nothing service-specific in it.
 */
public final class EndpointInventory {

	// Balances one level of nesting so a regex-constrained variable like {code:\d{3}} normalises
	// whole to {}, rather than a bare \{[^}]+} stopping at the constraint's own inner '}'.
	private static final Pattern PATH_VARIABLE = Pattern.compile("\\{(?:[^{}]|\\{[^{}]*})*}");

	private EndpointInventory() {
	}

	/** {@code VERB /path} for every endpoint the context exposes, path variables normalised to {@code {}}. */
	public static Set<String> exposedEndpoints(ApplicationContext context) {
		return handlerMethods(context).flatMap(EndpointInventory::expand)
			// Spring's own error dispatch, not an endpoint anyone routes to.
			.filter(endpoint -> !endpoint.endsWith(" /error"))
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Just the normalised paths, verb-independent — what an undeclared-route check needs. Derived
	 * from {@link #exposedEndpoints} (rather than its own traversal) so the two can never disagree
	 * on what counts as an endpoint — the {@code /error} exclusion included.
	 */
	public static Set<String> exposedPaths(ApplicationContext context) {
		return exposedEndpoints(context).stream()
			.map(endpoint -> endpoint.substring(endpoint.indexOf(' ') + 1))
			.collect(Collectors.toUnmodifiableSet());
	}

	private static Stream<RequestMappingInfo> handlerMethods(ApplicationContext context) {
		return context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
			.getHandlerMethods()
			.keySet()
			.stream();
	}

	private static Stream<String> expand(RequestMappingInfo info) {
		Set<String> verbs = verbsOf(info);
		return paths(info).flatMap(path -> verbs.stream().map(verb -> verb + " " + path));
	}

	private static Stream<String> paths(RequestMappingInfo info) {
		if (info.getPathPatternsCondition() == null) {
			return Stream.empty();
		}
		return info.getPathPatternsCondition()
			.getPatterns()
			.stream()
			.map(pattern -> PATH_VARIABLE.matcher(pattern.getPatternString()).replaceAll("{}"));
	}

	private static Set<String> verbsOf(RequestMappingInfo info) {
		return info.getMethodsCondition().getMethods().isEmpty() ? Set.of("GET")
				: info.getMethodsCondition().getMethods().stream().map(Enum::name).collect(Collectors.toSet());
	}

}
