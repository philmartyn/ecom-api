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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail onInvalidCredentials(InvalidCredentialsException ex,
                                             HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials", ex.getMessage(), request);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail onUserNotFound(UserNotFoundException ex,
                                        HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required", request);
    }

    /**
     * A catalog lookup that found no active product for the requested slug.
     * Mapped to 404, not 401: the route is public, so "not found" is the honest
     * answer. An inactive product is deliberately indistinguishable from a missing
     * one, or the catalog would leak which products were retired.
     */
    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail onProductNotFound(ProductNotFoundException ex,
                                           HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Product not found", "Product not found", request);
    }

    /**
     * The client addressed a cart line that is not there. PATCH has nothing to
     * update, so 404 is the honest answer; the client can POST to create it.
     */
    @ExceptionHandler(CartItemNotFoundException.class)
    public ProblemDetail onCartItemNotFound(CartItemNotFoundException ex,
                                            HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Cart item not found", "Cart item not found", request);
    }

    /**
     * The requested quantity is above current stock. 409, not 400: the request is
     * well-formed, it conflicts with the current state. The detail stays generic so
     * the exact inventory number is not exposed.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail onInsufficientStock(InsufficientStockException ex,
                                             HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Not enough stock", "Not enough stock available", request);
    }

    /**
     * Spring MVC could not convert a path variable (for example "abc" to long).
     * Without this, Boot's own advice would answer with a different shape.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex,
                                        HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Invalid request", request);
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

    @ExceptionHandler(EmptyCartException.class)
    public ProblemDetail onEmptyCart(EmptyCartException ex,
                                     HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Cart is empty", "Cart is empty", request);
    }

    @ExceptionHandler(CartNotCheckoutableException.class)
    public ProblemDetail onCartNotCheckoutable(CartNotCheckoutableException ex,
                                               HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                "Cart cannot be checked out",
                "One or more items are unavailable", request);
        problem.setProperty("unavailableItems", ex.getUnavailableItems());
        return problem;
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail onOrderNotFound(OrderNotFoundException ex,
                                         HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", "Order not found", request);
    }

    @ExceptionHandler(OrderNotCancellableException.class)
    public ProblemDetail onOrderNotCancellable(OrderNotCancellableException ex,
                                               HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT,
                "Order cannot be cancelled", "Order cannot be cancelled", request);
    }

    @ExceptionHandler(OrderNotPayableException.class)
    public ProblemDetail onOrderNotPayable(OrderNotPayableException ex,
                                           HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT,
                "Order is not payable", "Order is not payable", request);
    }

    @ExceptionHandler(PaymentInProgressException.class)
    public ProblemDetail onPaymentInProgress(PaymentInProgressException ex,
                                             HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT,
                "Payment already in progress",
                "A payment attempt is already open for this order", request);
    }

    @ExceptionHandler(OrderUnavailableException.class)
    public ProblemDetail onOrderUnavailable(OrderUnavailableException ex,
                                            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT,
                "Order items are unavailable",
                "One or more items are unavailable", request);
        problem.setProperty("unavailableItems", ex.getUnavailableItems());
        return problem;
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail onPaymentNotFound(PaymentNotFoundException ex,
                                           HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND,
                "Payment not found", "Payment not found", request);
    }

    @ExceptionHandler(PaymentNotCancellableException.class)
    public ProblemDetail onPaymentNotCancellable(PaymentNotCancellableException ex,
                                                 HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT,
                "Payment cannot be cancelled", "Payment cannot be cancelled", request);
    }

    @ExceptionHandler(PaymentProviderException.class)
    public ProblemDetail onPaymentProvider(PaymentProviderException ex,
                                           HttpServletRequest request) {
        return problem(HttpStatus.BAD_GATEWAY,
                "Payment provider unavailable",
                "The payment provider could not complete the request", request);
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ProblemDetail onInvalidWebhookSignature(InvalidWebhookSignatureException ex,
                                                   HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST,
                "Invalid webhook signature",
                "The Stripe-Signature header is missing or invalid", request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
