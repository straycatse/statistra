package com.straycat.statistra.controller;

import com.straycat.statistra.security.PayloadTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.kafka.KafkaException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns failures into consistent JSON rather than Spring's default error page.
 *
 * <p>Validation failures in particular need to say <em>which</em> field was
 * wrong; a bare 400 gives a client integrating against this nothing to act on.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.put(error.getField(), error.getDefaultMessage()));

        Map<String, Object> body = body("validation_failed", "Request validation failed");
        body.put("fields", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /** A body that exceeded the configured ceiling, thrown directly by the filter. */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(PayloadTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body("payload_too_large", e.getMessage()));
    }

    /**
     * Malformed JSON, or a value that cannot be coerced (a bad timestamp, say).
     *
     * <p>An oversized body also arrives here, because the size limit is enforced
     * inside the input stream and Spring wraps whatever the stream throws. Those
     * are unwrapped back to 413: "too big" and "unparseable" are different
     * problems and a client can only act on the difference if we report it.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        PayloadTooLargeException tooLarge = findTooLarge(e);
        if (tooLarge != null) {
            return handleTooLarge(tooLarge);
        }
        return ResponseEntity.badRequest()
                .body(body("malformed_request", "Request body could not be parsed"));
    }

    /**
     * The broker was unreachable or rejected the record.
     *
     * <p>503 rather than 500: nothing is wrong with the request, the dependency
     * is unavailable, and the distinction tells a client that retrying is
     * worthwhile.
     */
    @ExceptionHandler(KafkaException.class)
    public ResponseEntity<Map<String, Object>> handleKafka(KafkaException e) {
        log.error("Could not publish event to Kafka", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(body("upstream_unavailable", "Event could not be accepted, please retry"));
    }

    private PayloadTooLargeException findTooLarge(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLargeException tooLarge) {
                return tooLarge;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /** Raised by query parameter parsing: bad interval, filter, or groupBy. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body("bad_request", e.getMessage()));
    }

    /**
     * A query parameter that could not be converted to its declared type, such
     * as {@code ?from=yesterday} against an {@code Instant}.
     *
     * <p>Needs an explicit handler because, unlike most framework-level
     * rejections, this one does not implement {@link ErrorResponse}: it extends
     * {@code TypeMismatchException} from spring-beans, which predates that
     * interface. The generic branch below would therefore report it as a 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(body("bad_request",
                "Parameter '" + e.getName() + "' has an invalid value"));
    }

    /**
     * Everything not handled above.
     *
     * <p>The {@link ErrorResponse} branch is load-bearing rather than defensive.
     * Spring consults {@code ExceptionHandlerExceptionResolver} before its own
     * {@code DefaultHandlerExceptionResolver}, so an advice that declares
     * {@code Exception} outranks the framework's handling of its own exceptions.
     * Without this check, an unknown path, a wrong HTTP method, an unsupported
     * content type, or a missing required parameter all collapsed into a 500,
     * telling a client "we broke" when the truth was "your request was wrong".
     *
     * <p>Deferring to the status the exception already carries covers that whole
     * family at once, including any added by future Spring versions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            log.debug("Rejecting request with {}: {}", status, e.getMessage());
            return ResponseEntity.status(status)
                    .body(body(reasonFor(status), e.getMessage()));
        }

        // Log the detail, return none. Stack traces and driver messages can
        // disclose schema and infrastructure to a caller.
        log.error("Unhandled exception serving request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("internal_error", "An unexpected error occurred"));
    }

    /** A stable, snake_case error code derived from the status, e.g. {@code not_found}. */
    private String reasonFor(HttpStatusCode status) {
        if (status instanceof HttpStatus resolved) {
            return resolved.getReasonPhrase().toLowerCase(Locale.ROOT).replace(' ', '_');
        }
        return "error";
    }

    private Map<String, Object> body(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
