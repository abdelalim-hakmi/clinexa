package com.clinexa.identity.practitioner;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Immutable;

import com.clinexa.identity.common.BaseEntity;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Reference data changed only by migrations: immutable, so a read-only second-level cache is safe.
 * Hibernate keys each entry with the session's tenant id, so every clinic caches its own copy.
 */
@Entity
@Table(name = "specialty")
@Immutable
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
public class Specialty extends BaseEntity {

	@Column(name = "slug", nullable = false, updatable = false)
	private String slug;

	@Column(name = "label", nullable = false, updatable = false)
	private String label;

	protected Specialty() {
	}

	public Specialty(String slug, String label) {
		this.slug = slug;
		this.label = label;
	}

	public String getSlug() {
		return slug;
	}

	public String getLabel() {
		return label;
	}

}
