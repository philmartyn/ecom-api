package com.example.ecom.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ordering matters: with spring.mvc.problemdetails.enabled=true, Boot registers its
 * own ProblemDetailsExceptionHandler ahead of any unannotated advice. Without this,
 * it wins for MethodArgumentNotValidException and the "errors" map never appears.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException ex,
                                             HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid", request);

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail onInvalidRequest(InvalidRequestException ex,
                                          HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage(), request);
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ProblemDetail onEmailAlreadyUsed(EmailAlreadyUsedException ex,
                                            HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Email already registered", ex.getMessage(), request);
    }

    /**
     * Backstop for the race the service's existsByEmail check cannot win: two
     * concurrent registrations both pass the check, and the unique index rejects
     * the second. Deliberately generic - a constraint violation does not say which
     * constraint, and guessing "email" will be wrong once other unique columns exist.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrityViolation(DataIntegrityViolationException ex,
                                                  HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict",
                "The request conflicts with existing data", request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
