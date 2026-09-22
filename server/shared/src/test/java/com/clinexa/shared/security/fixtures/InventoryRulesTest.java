package com.clinexa.shared.security.fixtures;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.tngtech.archunit.core.importer.ClassFileImporter;

import jakarta.persistence.EntityManager;

/**
 * The inventory rules, made to fail on purpose.
 * <p>
 * An inventory rule nobody has seen turn red is a rule nobody knows works (guide 10.8). The services
 * only ever show these rules passing, so this class feeds each of them a sample that must be caught —
 * and one that must not, so a rule that rejects everything cannot pass either.
 */
class InventoryRulesTest {

	private static final String EXEMPTION = "com.clinexa.shared.Derogation";

	/** {@code @NativeQuery} is {@code @Query(nativeQuery = true)} under another name. */
	@SuppressWarnings("unused")
	interface NativeQueryRepository extends Repository<Object, UUID> {

		@NativeQuery("select * from record")
		List<Object> all();

	}

	@SuppressWarnings("unused")
	interface NativeFlaggedQueryRepository extends Repository<Object, UUID> {

		@Query(value = "select * from record", nativeQuery = true)
		List<Object> all();

	}

	@SuppressWarnings("unused")
	interface HarmlessJpqlRepository extends Repository<Object, UUID> {

		@Query("select r from Record r where r.name = :name")
		List<Object> byName(String name);

	}

	@SuppressWarnings("unused")
	static class BypassingService {

		private EntityManager em;

		Object read() {
			return this.em.createNativeQuery("select * from record").getResultList();
		}

	}

	@SuppressWarnings("unused")
	static class HarmlessService {

		private EntityManager em;

		Object read() {
			return this.em.createQuery("select r from Record r").getResultList();
		}

	}

	@Test
	void inv3CatchesANativeQueryDeclaredByMetaAnnotation() {
		assertThatThrownBy(() -> InventoryRules.noUndeclaredNativeQuery(EXEMPTION)
			.check(new ClassFileImporter().importClasses(NativeQueryRepository.class)))
			.hasMessageContaining("no @DerogationTenant");
	}

	@Test
	void inv3CatchesAQueryWithNativeTrue() {
		assertThatThrownBy(() -> InventoryRules.noUndeclaredNativeQuery(EXEMPTION)
			.check(new ClassFileImporter().importClasses(NativeFlaggedQueryRepository.class)))
			.hasMessageContaining("no @DerogationTenant");
	}

	@Test
	void inv3LetsThroughAnOrdinaryJpql() {
		assertThatCode(() -> InventoryRules.noUndeclaredNativeQuery(EXEMPTION)
			.check(new ClassFileImporter().importClasses(HarmlessJpqlRepository.class))).doesNotThrowAnyException();
	}

	@Test
	void inv3CatchesNativeSqlIssuedDirectly() {
		assertThatThrownBy(() -> InventoryRules.noDirectNativeSql()
			.check(new ClassFileImporter().importClasses(BypassingService.class)))
			.hasMessageContaining("createNativeQuery");
	}

	@Test
	void inv3LetsThroughADirectJpqlQuery() {
		assertThatCode(() -> InventoryRules.noDirectNativeSql()
			.check(new ClassFileImporter().importClasses(HarmlessService.class))).doesNotThrowAnyException();
	}

}
