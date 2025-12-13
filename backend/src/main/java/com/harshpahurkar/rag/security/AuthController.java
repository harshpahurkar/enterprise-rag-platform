package com.harshpahurkar.rag.security;

import java.time.Instant;
import java.util.List;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harshpahurkar.rag.AppProperties;

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

	record Me(String username, List<String> roles) {
	}

	private final AuthenticationManager auth;

	private final JwtEncoder encoder;

	private final AppProperties props;

	AuthController(AuthenticationManager auth, JwtEncoder encoder, AppProperties props) {
		this.auth = auth;
		this.encoder = encoder;
		this.props = props;
	}

	/** Bad credentials surface as 401 ProblemDetail via {@link ApiExceptionHandler}. */
	@PostMapping("/login")
	LoginResponse login(@Valid @RequestBody LoginRequest req) {
		Authentication user = auth.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
		List<String> roles = user.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(a -> a.startsWith("ROLE_")) // Security 7 also adds FACTOR_PASSWORD
			.map(a -> a.substring("ROLE_".length()))
			.toList();
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(user.getName())
			.issuedAt(now)
			.expiresAt(now.plus(props.jwt().ttl()))
			.claim("roles", roles)
			.build();
		String token = encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return new LoginResponse(token, user.getName(), roles);
	}

	@GetMapping("/me")
	Me me(@AuthenticationPrincipal Jwt jwt) {
		return new Me(jwt.getSubject(), jwt.getClaimAsStringList("roles"));
	}

}
