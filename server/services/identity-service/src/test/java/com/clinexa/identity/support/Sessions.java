package com.clinexa.identity.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.clinexa.shared.security.fixtures.SecurityFixtures.AccountFixture;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs a fixture in through the real route and hands back the session cookie.
 * <p>
 * Every test that needs an authenticated caller goes through here rather than through a
 * {@code @WithMockUser}-style shortcut. A shortcut would hand the test a principal the real sign-in
 * never produces, and the interesting part of this foundation is precisely what the real principal
 * does <em>not</em> carry — no member, no authority.
 */
public final class Sessions {

	private final MockMvc mvc;

	private final JsonMapper json;

	public Sessions(MockMvc mvc, JsonMapper json) {
		this.mvc = mvc;
		this.json = json;
	}

	/** The cookies of a fresh session for this account. */
	public Cookie[] login(AccountFixture account) {
		try {
			MvcResult result = this.mvc
				.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content(this.json.writeValueAsString(Map.of("email", account.email(), "password",
							com.clinexa.shared.security.fixtures.SecurityFixtures.PASSWORD)))
					.with(csrf()))
				.andReturn();
			if (result.getResponse().getStatus() != 200) {
				throw new IllegalStateException("Sign-in refused for " + account.email() + ": "
						+ result.getResponse().getStatus() + " " + result.getResponse().getContentAsString());
			}
			return result.getResponse().getCookies();
		}
		catch (Exception e) {
			throw new IllegalStateException("Could not sign in " + account.email(), e);
		}
	}

}
