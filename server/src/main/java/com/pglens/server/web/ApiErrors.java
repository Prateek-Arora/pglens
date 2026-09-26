package com.pglens.server.web;

import com.pglens.server.auth.AuthExceptions;
import com.pglens.server.errors.Errors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain failures to RFC 9457 problem responses. Unexpected errors stay a plain 500. */
@RestControllerAdvice
class ApiErrors {

  @ExceptionHandler(AuthExceptions.LoginFailed.class)
  ProblemDetail loginFailed(AuthExceptions.LoginFailed e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  @ExceptionHandler(AuthExceptions.LoginThrottled.class)
  ResponseEntity<ProblemDetail> throttled(AuthExceptions.LoginThrottled e) {
    long seconds = Math.max(1, e.retryAfter().toSeconds());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
  }

  @ExceptionHandler(Errors.Conflict.class)
  ProblemDetail conflict(Errors.Conflict e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(Errors.NotFound.class)
  ProblemDetail notFound(Errors.NotFound e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(Errors.Invalid.class)
  ProblemDetail invalid(Errors.Invalid e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
