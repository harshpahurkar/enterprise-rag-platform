package com.harshpahurkar.rag.security;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationEvent;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Security events as one {@code event=name key=value ...} line each on the {@code SECURITY_AUDIT} logger, so they
 * can be routed and kept apart from application logs. Callers pass identifiers only: never passwords, tokens,
 * question text, document text or other personal data.
 * <p>
 * Every value is neutralized against log injection (CWE-117): control characters, including CR and LF, become
 * {@code _}, and a value with a space, {@code =} or {@code "} is quoted so it can't forge another field.
 */
@Component
public class SecurityAudit {

	private static final Logger log = LoggerFactory.getLogger("SECURITY_AUDIT");

	/** C0 and C1 controls (CR, LF, NEL, ...) plus the Unicode line and paragraph separators. */
	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");

	private static final Pattern NEEDS_QUOTES = Pattern.compile("[\\s=\"]");

	/** Logs {@code event=name} followed by the key/value pairs in order. */
	public static void log(String event, Object... keysAndValues) {
		var line = new StringBuilder("event=").append(event);
		for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
			line.append(' ').append(keysAndValues[i]).append('=').append(value(keysAndValues[i + 1]));
		}
		log.info(line.toString());
	}

	static String value(Object value) {
		String text = CONTROL.matcher(String.valueOf(value)).replaceAll("_");
		if (text.isEmpty() || NEEDS_QUOTES.matcher(text).find()) {
			return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
		}
		return text;
	}

	/** Username/password logins only: bearer-token checks on every request publish these events too. */
	@EventListener
	void login(AbstractAuthenticationEvent event) {
		Authentication auth = event.getAuthentication();
		if (!(auth instanceof UsernamePasswordAuthenticationToken)) {
			return;
		}
		String ip = auth.getDetails() instanceof WebAuthenticationDetails details ? details.getRemoteAddress() : "-";
		if (event instanceof AuthenticationSuccessEvent) {
			log("login_success", "user", auth.getName(), "ip", ip);
		}
		else if (event instanceof AbstractAuthenticationFailureEvent) {
			log("login_failure", "user", auth.getName(), "ip", ip);
		}
	}

	/** Logs a 401, then lets the wrapped entry point write the response. */
	static AuthenticationEntryPoint audited(AuthenticationEntryPoint entryPoint) {
		return (request, response, e) -> {
			log("auth_rejected", "reason", e instanceof OAuth2AuthenticationException ? "invalid_token" : "no_token",
					"user", "-", "method", request.getMethod(), "path", request.getRequestURI());
			entryPoint.commence(request, response, e);
		};
	}

	/** Logs a 403, then lets the wrapped handler write the response. */
	static AccessDeniedHandler audited(AccessDeniedHandler handler) {
		return (request, response, e) -> {
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			log("access_denied", "user", auth == null ? "-" : auth.getName(), "method", request.getMethod(), "path",
					request.getRequestURI());
			handler.handle(request, response, e);
		};
	}

}
