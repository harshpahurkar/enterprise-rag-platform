package com.harshpahurkar.rag.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.harshpahurkar.rag.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Stateless JWT security. Tokens are HMAC-signed by this app ({@link AuthController}) and carry only identity:
 * subject, issuer and expiry. Roles are read from {@code app_user} on every request, so a demotion or deletion
 * applies to tokens already issued. Document-level access is enforced separately, inside the retrieval SQL.
 * Method security repeats the ADMIN check on the controller methods that write, so a URL rule that drifts can't
 * open them.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	static final String ISSUER = "rag-platform";

	private static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
			+ "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

	@Bean
	SecurityFilterChain api(HttpSecurity http, UserDetailsService users) throws Exception {
		http.csrf(csrf -> csrf.disable()) // bearer tokens only, no cookies: nothing for CSRF to ride on
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, "/api/auth/login")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/documents")
				.hasRole("ADMIN")
				.requestMatchers(HttpMethod.DELETE, "/api/documents/**")
				.hasRole("ADMIN")
				.requestMatchers("/api/**")
				.authenticated()
				.anyRequest()
				.permitAll()) // the built SPA's static files
			.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromDatabase(users))))
			.headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP)));
		return http.build();
	}

	/** One indexed lookup per request. A user deleted since login gets 401, like any other bad token. */
	static JwtAuthenticationConverter rolesFromDatabase(UserDetailsService users) {
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			try {
				return List.copyOf(users.loadUserByUsername(jwt.getSubject()).getAuthorities());
			}
			catch (UsernameNotFoundException e) {
				throw new InvalidBearerTokenException("Unknown user");
			}
		});
		return converter;
	}

	@Bean
	SecretKey jwtKey(AppProperties props) {
		String secret = props.jwt().secret();
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("JWT_SECRET must be set and at least 32 bytes (see .env.example)");
		}
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).build();
		// The defaults check exp only when present; a token without one would never expire.
		decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(new JwtIssuerValidator(ISSUER),
				new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull)));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	UserDetailsService users(JdbcClient jdbc) {
		return username -> jdbc.sql("SELECT username, password_hash, roles FROM app_user WHERE username = ?")
			.param(username)
			.query((rs, n) -> User.withUsername(rs.getString(1))
				.password(rs.getString(2))
				.roles((String[]) rs.getArray(3).getArray())
				.build())
			.optional()
			.orElseThrow(() -> new UsernameNotFoundException(username));
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
		var provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(encoder);
		return new ProviderManager(List.of(provider));
	}

}
