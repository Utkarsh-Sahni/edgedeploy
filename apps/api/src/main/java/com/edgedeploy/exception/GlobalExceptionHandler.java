package com.edgedeploy.exception;

import com.edgedeploy.config.RequestIdFilter;
import com.edgedeploy.github.GitHubApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders every error as an RFC 9457 {@link ProblemDetail}. Spring MVC's own exceptions are handled
 * by the superclass; this class adds domain exceptions, field-level validation details and a
 * catch-all that never leaks internals. Every problem carries the request id for support/debugging.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", ex.getMessage());
        problem.setProperty("errors", Map.of(ex.field(), ex.getMessage()));
        return problem;
    }

    /**
     * GitHub failures. A revoked token surfaces as 401 with code {@code github_reauth_required} so the
     * dashboard can send the user through sign-in again; upstream details are never echoed.
     */
    @ExceptionHandler(GitHubApiException.class)
    ProblemDetail handleGitHub(GitHubApiException ex) {
        ProblemDetail problem = switch (ex.kind()) {
            case UNAUTHORIZED -> problem(HttpStatus.UNAUTHORIZED, "GitHub authorization required",
                    "Your GitHub authorization has expired or was revoked. Sign in again.");
            case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "Not found on GitHub",
                    "The repository or branch does not exist, or your GitHub account cannot access it");
            case RATE_LIMITED -> problem(HttpStatus.TOO_MANY_REQUESTS, "GitHub rate limit",
                    "GitHub's API rate limit was reached. Try again in a few minutes.");
            case UNAVAILABLE, OTHER -> problem(HttpStatus.BAD_GATEWAY, "GitHub unavailable",
                    "GitHub could not be reached. Try again shortly.");
        };
        problem.setProperty("code", switch (ex.kind()) {
            case UNAUTHORIZED -> "github_reauth_required";
            case NOT_FOUND -> "github_not_found";
            case RATE_LIMITED -> "github_rate_limited";
            case UNAVAILABLE, OTHER -> "github_unavailable";
        });
        if (ex.kind() != GitHubApiException.Kind.NOT_FOUND) {
            log.warn("GitHub call failed: {} (upstream status {})", ex.kind(), ex.upstreamStatus());
        }
        return problem;
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    ProblemDetail handleUnauthenticated(AuthenticationCredentialsNotFoundException ex) {
        ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, "Unauthenticated", "Sign in with GitHub to continue");
        problem.setProperty("code", "unauthenticated");
        return problem;
    }

    /** A unique/foreign-key constraint won a race that the service-level pre-checks could not see. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Conflict", "The request conflicts with existing data");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "Parameter '" + ex.getName() + "' has an invalid value");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred. Quote the requestId when reporting this.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Adds the request id to problems produced by the superclass for standard MVC exceptions. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withRequestId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return withRequestId(problem);
    }

    private static ProblemDetail withRequestId(ProblemDetail problem) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }
}
