package com.harshpahurkar.rag.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fixed-window limits under {@code app.rate-limit.*}: login per client IP (password guessing), ask per user (LLM
 * cost). Over the limit the caller gets a 429 ProblemDetail with {@code Retry-After} in seconds. The client IP is
 * the socket address, never X-Forwarded-For, which any client can set.
 */
// ponytail: single-instance memory; move to Redis or Bucket4j for multiple instances.
@Configuration
@EnableConfigurationProperties(RateLimits.Props.class)
class RateLimits implements WebMvcConfigurer {

	@ConfigurationProperties("app.rate-limit")
	record Props(Limit login, Limit ask) {
		record Limit(int requests, Duration window) {
		}
	}

	private final Props props;

	RateLimits(Props props) {
		this.props = props;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(limit(props.login(), HttpServletRequest::getRemoteAddr))
			.addPathPatterns("/api/auth/login");
		// Runs after the security filters, so the caller is already authenticated.
		registry.addInterceptor(limit(props.ask(), req -> req.getUserPrincipal().getName())).addPathPatterns("/api/ask");
	}

	static HandlerInterceptor limit(Props.Limit limit, Function<HttpServletRequest, String> key) {
		var windows = new FixedWindows(limit.requests(), limit.window());
		return new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
				long retryAfter = windows.hit(key.apply(req));
				if (retryAfter > 0) {
					var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
							"Too many requests. Try again in " + retryAfter + " seconds.");
					var e = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, problem, null);
					e.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
					throw e;
				}
				return true;
			}
		};
	}

	/** Hits per key in fixed windows. */
	static final class FixedWindows {

		private record Window(long start, int hits) {
		}

		private final Map<String, Window> windows = new ConcurrentHashMap<>();

		private final int limit;

		private final long windowMs;

		FixedWindows(int limit, Duration window) {
			this.limit = limit;
			this.windowMs = window.toMillis();
		}

		/** Counts a hit; returns 0 if allowed, else the whole seconds until this key's window resets. */
		long hit(String key) {
			long now = System.currentTimeMillis();
			if (windows.size() > 10_000) { // expired keys would otherwise pile up forever
				windows.values().removeIf(w -> now - w.start() >= windowMs);
			}
			Window w = windows.compute(key, (k, old) -> old == null || now - old.start() >= windowMs
					? new Window(now, 1) : new Window(old.start(), old.hits() + 1));
			return w.hits() <= limit ? 0 : Math.max(1, Math.ceilDiv(w.start() + windowMs - now, 1000));
		}

	}

}
