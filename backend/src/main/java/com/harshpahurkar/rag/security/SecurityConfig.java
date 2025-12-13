package com.harshpahurkar.rag.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.harshpahurkar.rag.AppProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Stateless JWT security. Tokens are HMAC-signed by this app ({@link AuthController}) and carry a
 * {@code roles} claim, mapped to {@code ROLE_*} authorities. Document-level access is enforced
 * separately, inside the retrieval SQL.
 */
@Configuration
public class SecurityConfig {

	private static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
			+ "frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

	@Bean
	SecurityFilterChain api(HttpSecurity http) throws Exception {
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
			.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
			.headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP)));
		return http.build();
	}

	static JwtAuthenticationConverter rolesConverter() {
		var authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("roles");
		authorities.setAuthorityPrefix("ROLE_");
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
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
		return NimbusJwtDecoder.withSecretKey(jwtKey).build();
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
