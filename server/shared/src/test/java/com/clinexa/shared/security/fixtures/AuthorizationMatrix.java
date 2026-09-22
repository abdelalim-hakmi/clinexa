package com.clinexa.shared.security.fixtures;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads a service's {@code matrice.csv} — the machine-readable form of
 * {@code _docs/securite/matrice-autorisation.md}.
 * <p>
 * It exists so that F2 is a <strong>parameterised</strong> test with one execution per cell, rather
 * than a hand-written test per endpoint: written by hand, cells get forgotten, and the ones that get
 * forgotten are the {@code ❌} — which are precisely the ones that prove something. Verifying that a
 * practitioner can read a record proves nothing about isolation; verifying that the receptionist
 * cannot does.
 * <p>
 * The same file is the reference of INV-1: every endpoint the service actually exposes must appear
 * in it, so a route added without updating the matrix fails the build.
 */
public final class AuthorizationMatrix {

	private static final String FILE = "matrice.csv";

	private AuthorizationMatrix() {
	}

	/** Every cell of the matrix of the service whose test classpath is being read. */
	public static List<Cell> cells() {
		try (InputStream stream = AuthorizationMatrix.class.getClassLoader().getResourceAsStream(FILE)) {
			if (stream == null) {
				throw new IllegalStateException(FILE + " not found: the matrix must precede the code.");
			}
			List<Cell> cells = new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String cleaned = line.strip();
					if (cleaned.isEmpty() || cleaned.startsWith("#") || cleaned.startsWith("route;")) {
						continue;
					}
					String[] fields = cleaned.split(";");
					if (fields.length != 4) {
						throw new IllegalStateException("Malformed line in " + FILE + ": " + line);
					}
					cells.add(new Cell(fields[0], fields[1], fields[2], Boolean.parseBoolean(fields[3])));
				}
			}
			if (cells.isEmpty()) {
				throw new IllegalStateException(FILE + " is empty: a test that runs nothing is green for nothing.");
			}
			return List.copyOf(cells);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The distinct route patterns the matrix declares — what INV-1 compares the endpoints to. */
	public static Set<String> declaredRoutes() {
		return cells().stream().map(Cell::route).collect(Collectors.toUnmodifiableSet());
	}

	/** The distinct {@code route + verb} pairs, for the stricter half of INV-1. */
	public static Set<String> declaredRoutesAndVerbs() {
		return cells().stream()
			.map(cell -> cell.verb() + " " + cell.route())
			.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * One cell: a route, a verb, a role, and whether that role passes the route filter.
	 *
	 * @param route URL pattern with {@code {}} for each variable, so it compares with Spring's own
	 * patterns without depending on the names of the path variables
	 * @param role a {@code ClinicRole} name, or {@code ANONYMOUS} for a request with no session
	 * @param allowed {@code true} means "not refused by the route filter" — the response may still
	 * be a {@code 404}; {@code false} means {@code 401} for {@code ANONYMOUS} and {@code 403} otherwise
	 */
	public record Cell(String route, String verb, String role, boolean allowed) {

		public static final String ANONYMOUS = "ANONYMOUS";

		public boolean anonymous() {
			return ANONYMOUS.equals(this.role);
		}

		@Override
		public String toString() {
			return "%s %s · %s · %s".formatted(this.verb, this.route, this.role, this.allowed ? "✅" : "❌");
		}

	}

}
