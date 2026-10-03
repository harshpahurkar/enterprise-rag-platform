package com.harshpahurkar.rag.security;

import java.time.Instant;
import java.util.List;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harshpahurkar.rag.AppProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 200) String password) {
	}

	record LoginResponse(String token, String username, List<String> roles) {
	}

	private final AuthenticationManager auth;

	private final JwtEncoder encoder;

	private final AppProperties props;

	AuthController(AuthenticationManager auth, JwtEncoder encoder, AppProperties props) {
		this.auth = auth;
		this.encoder = encoder;
		this.props = props;
	}

	/**
	 * Bad credentials surface as 401 ProblemDetail via {@link ApiExceptionHandler}. The token holds no roles (they are
	 * read from the database per request); the body lists them for the UI. The request details carry the client IP
	 * into the login events that {@link SecurityAudit} logs.
	 */
	@PostMapping("/login")
	LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
		var attempt = UsernamePasswordAuthenticationToken.unauthenticated(req.username(), req.password());
		attempt.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
		Authentication user = auth.authenticate(attempt);
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(SecurityConfig.ISSUER)
			.subject(user.getName())
			.issuedAt(now)
			.expiresAt(now.plus(props.jwt().ttl()))
			.build();
		String token = encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return new LoginResponse(token, user.getName(), Roles.of(user));
	}

}
