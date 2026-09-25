package com.example.currencyconverter.controller.advice;

import com.example.currencyconverter.client.exception.TreasuryException;
import com.example.currencyconverter.exception.ExchangeRateNotFoundException;
import com.example.currencyconverter.exception.TransactionNotFoundException;
import com.example.currencyconverter.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail transactionNotFound(TransactionNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.TRANSACTION_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ExchangeRateNotFoundException.class)
    ProblemDetail rateNotFound(ExchangeRateNotFoundException exception) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.EXCHANGE_RATE_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(TreasuryException.class)
    ProblemDetail treasuryFailure(TreasuryException exception) {
        log.warn("Treasury request failed", exception);
        return problem(exception.isUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                exception.isUnavailable() ? ErrorCode.TREASURY_UNAVAILABLE : ErrorCode.TREASURY_INVALID_RESPONSE,
                exception.getPublicMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpectedFailure(Exception exception) {
        log.error("Unexpected request failure", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    }

    @Override
    @Nullable
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException exception,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Request validation failed");
        body.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage())).toList());
        return handleExceptionInternal(exception, body, headers, status, request);
    }

    @Override
    @Nullable
    protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception exception, @Nullable Object body,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        if (body == null) {
            body = problem(status, status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST,
                    status.is5xxServerError() ? "An unexpected error occurred"
                            : "Invalid request. Check parameters, JSON types and date format (yyyy-MM-dd)");
        } else if (body instanceof ProblemDetail detail
                && (detail.getProperties() == null || !detail.getProperties().containsKey("code"))) {
            detail.setProperty("code", status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST);
        }
        return super.handleExceptionInternal(exception, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatusCode status, ErrorCode code, String message) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, message);
        body.setProperty("code", code);
        return body;
    }

    private record FieldViolation(String field, String message) {
    }
}
