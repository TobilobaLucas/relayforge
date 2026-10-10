package dev.relayforge.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@org.springframework.core.annotation.Order(-1)
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail domain(ApiException e) {
        return Problems.create(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        HandlerMethodValidationException.class,
        ServletRequestBindingException.class,
        jakarta.validation.ConstraintViolationException.class
    })
    ProblemDetail invalid(Exception e) {
        return Problems.create(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Check the request body, headers, and query parameters against the API schema.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail missingRoute(NoResourceFoundException e) {
        return Problems.create(
                HttpStatus.NOT_FOUND, "resource_not_found", "The requested route was not found.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(HttpRequestMethodNotSupportedException e) {
        return Problems.create(
                HttpStatus.METHOD_NOT_ALLOWED,
                "method_not_allowed",
                "This route does not support the request method.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail media(HttpMediaTypeNotSupportedException e) {
        return Problems.create(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_media_type",
                "Use Content-Type: application/json.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected request failure", e);
        return Problems.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error",
                "The request could not be completed. Use the request ID when contacting the operator.");
    }
}
