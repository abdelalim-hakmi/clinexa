package com.clinexa.identity.clinic;

import java.time.ZoneId;

import com.clinexa.identity.common.ActivityStatus;
import com.clinexa.identity.common.AuditTrail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The tenant: its own id is the discriminant, so there is no clinic_id and no @TenantId here.
 * Access to a clinic must be authorized by the tenant resolution (L1, guide 6.1). Not cached:
 * it changes with onboarding and deactivation.
 */
@Entity
@Table(name = "clinic")
public class Clinic extends AuditTrail {

	@Column(name = "legal_name", nullable = false)
	private String legalName;

	@Column(name = "ice", nullable = false)
	private String ice;

	@Column(name = "tax_id")
	private String taxId;

	@Column(name = "address")
	private String address;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "city_id")
	private City city;

	@Column(name = "timezone", nullable = false)
	private ZoneId timezone = ZoneId.of("Africa/Casablanca");

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ActivityStatus status = ActivityStatus.ACTIVE;

	protected Clinic() {
	}

	public Clinic(String legalName, String ice) {
		this.legalName = legalName;
		this.ice = ice;
	}

	public String getLegalName() {
		return legalName;
	}

	public void setLegalName(String legalName) {
		this.legalName = legalName;
	}

	public String getIce() {
		return ice;
	}

	public void setIce(String ice) {
		this.ice = ice;
	}

	public String getTaxId() {
		return taxId;
	}

	public void setTaxId(String taxId) {
		this.taxId = taxId;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public City getCity() {
		return city;
	}

	public void setCity(City city) {
		this.city = city;
	}

	public ZoneId getTimezone() {
		return timezone;
	}

	public void setTimezone(ZoneId timezone) {
		this.timezone = timezone;
	}

	public ActivityStatus getStatus() {
		return status;
	}

	public void setStatus(ActivityStatus status) {
		this.status = status;
	}

}
