package com.example.hotelmanagement.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Slf4j
@lombok.RequiredArgsConstructor
@ControllerAdvice(basePackages = "com.example.hotelmanagement.web")
public class WebExceptionHandler {
    private final com.example.hotelmanagement.common.util.CmsMessages messages;

    @ExceptionHandler(ResourceNotFoundException.class)
    public String handleNotFound(
            ResourceNotFoundException ex, HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        model.addAttribute("status", 404);
        model.addAttribute("errorTitle", "Resource Not Found");
        model.addAttribute("errorMessage", ex.getMessage());
        return "admin/error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(
            AccessDeniedException ex, HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        model.addAttribute("status", 403);
        model.addAttribute("errorTitle", "Access Denied");
        model.addAttribute("errorMessage", "You do not have permission to access this page.");
        return "admin/error";
    }

    @ExceptionHandler(AppException.class)
    public String handleAppException(AppException ex, HttpServletResponse response, Model model) {
        response.setStatus(ex.getErrorCode().getStatus().value());
        model.addAttribute("status", ex.getErrorCode().getStatus().value());
        model.addAttribute("errorTitle", ex.getErrorCode().getTitle());
        model.addAttribute("errorMessage", ex.getMessage());
        return "admin/error";
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public String handleOversizedUpload(HttpServletResponse response, Model model) {
        response.setStatus(413);
        model.addAttribute("status", 413);
        model.addAttribute("errorTitle", messages.text("hotel.error.uploadTitle"));
        model.addAttribute("errorMessage", messages.text("hotel.error.imageFile"));
        return "admin/error";
    }

    @ExceptionHandler(Exception.class)
    public String handleUnexpected(
            Exception ex, HttpServletRequest request, HttpServletResponse response, Model model) {
        log.error("Unhandled web exception on {}", request.getRequestURI(), ex);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        model.addAttribute("status", 500);
        model.addAttribute("errorTitle", "Unexpected Error");
        model.addAttribute("errorMessage", "An unexpected error occurred. Please try again later.");
        return "admin/error";
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public String handleConflict(HttpServletResponse response, Model model) {
        response.setStatus(409);
        model.addAttribute("status", 409);
        model.addAttribute("errorTitle", messages.text("cms.error.conflictTitle"));
        model.addAttribute("errorMessage", messages.text("cms.error.conflict"));
        return "admin/error";
    }

    @ExceptionHandler(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public String handleInvalidFilter(HttpServletResponse response, Model model) {
        response.setStatus(400);
        model.addAttribute("status", 400);
        model.addAttribute("errorTitle", messages.text("cms.error.inputTitle"));
        model.addAttribute("errorMessage", messages.text("cms.error.filter"));
        return "admin/error";
    }
}
