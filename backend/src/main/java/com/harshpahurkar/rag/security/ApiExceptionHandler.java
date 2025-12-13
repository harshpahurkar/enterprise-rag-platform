package com.harshpahurkar.rag.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	/** Same message for unknown user and wrong password, so logins can't probe for usernames. */
	@ExceptionHandler(AuthenticationException.class)
	ProblemDetail badLogin(AuthenticationException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password");
	}

}
