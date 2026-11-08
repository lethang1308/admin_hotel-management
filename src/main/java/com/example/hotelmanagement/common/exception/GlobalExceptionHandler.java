package com.example.hotelmanagement.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> uploadTooLarge(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(413)
                .body(
                        ProblemDetailFactory.build(
                                ErrorCode.PAYLOAD_TOO_LARGE,
                                ErrorCode.PAYLOAD_TOO_LARGE.getDefaultMessage(),
                                request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage() != null
                            ? fieldError.getDefaultMessage()
                            : "Invalid value");
        }
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.VALIDATION_ERROR,
                        "One or more request fields failed validation",
                        request,
                        fieldErrors);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus()).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        Map<String, String> violations = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path =
                    violation.getPropertyPath() != null
                            ? violation.getPropertyPath().toString()
                            : "parameter";
            violations.putIfAbsent(path, violation.getMessage());
        }
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.VALIDATION_ERROR,
                        "Constraint validation failed",
                        request,
                        violations);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getStatus()).body(problem);
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleAppException(
            AppException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn(
                "Application error [{}] on {}: {}",
                errorCode.name(),
                request.getRequestURI(),
                ex.getMessage());
        ProblemDetail problem = ProblemDetailFactory.build(errorCode, ex.getMessage(), request);
        return ResponseEntity.status(errorCode.getStatus()).body(problem);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.INVALID_CREDENTIALS,
                        ErrorCode.INVALID_CREDENTIALS.getDefaultMessage(),
                        request);
        return ResponseEntity.status(ErrorCode.INVALID_CREDENTIALS.getStatus()).body(problem);
    }

    @ExceptionHandler({DisabledException.class, LockedException.class})
    public ResponseEntity<ProblemDetail> handleAccountDisabled(
            RuntimeException ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.ACCOUNT_DISABLED,
                        ErrorCode.ACCOUNT_DISABLED.getDefaultMessage(),
                        request);
        return ResponseEntity.status(ErrorCode.ACCOUNT_DISABLED.getStatus()).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(
            AuthenticationException ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.UNAUTHORIZED,
                        ErrorCode.UNAUTHORIZED.getDefaultMessage(),
                        request);
        return ResponseEntity.status(ErrorCode.UNAUTHORIZED.getStatus()).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage(), request);
        return ResponseEntity.status(ErrorCode.FORBIDDEN.getStatus()).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Database integrity violation on {}", request.getRequestURI());
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.DUPLICATE_RESOURCE,
                        "A conflicting record already exists or violates data constraints",
                        request);
        return ResponseEntity.status(ErrorCode.DUPLICATE_RESOURCE.getStatus()).body(problem);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(
            Exception ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.BAD_REQUEST,
                        "Malformed request payload or invalid query parameter",
                        request);
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.getStatus()).body(problem);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        ErrorCode.RESOURCE_NOT_FOUND.getDefaultMessage(),
                        request);
        return ResponseEntity.status(ErrorCode.RESOURCE_NOT_FOUND.getStatus()).body(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.METHOD_NOT_ALLOWED,
                        ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage(),
                        request);
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        ProblemDetail problem =
                ProblemDetailFactory.build(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        ErrorCode.INTERNAL_SERVER_ERROR.getDefaultMessage(),
                        request);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus()).body(problem);
    }
}
