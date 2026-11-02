package com.example.hotelmanagement.common.exception;

import com.example.hotelmanagement.common.logging.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.util.StringUtils;

public final class ProblemDetailFactory {

    private ProblemDetailFactory() {
    }

    public static ProblemDetail build(
            ErrorCode errorCode,
            String detail,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {
        String resolvedDetail = StringUtils.hasText(detail) ? detail : errorCode.getDefaultMessage();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), resolvedDetail);
        problem.setTitle(errorCode.getTitle());
        if (request != null && StringUtils.hasText(request.getRequestURI())) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        problem.setProperty("errorCode", errorCode.name());
        problem.setProperty("timestamp", Instant.now().toString());

        String correlationId = resolveCorrelationId(request);
        if (StringUtils.hasText(correlationId)) {
            problem.setProperty("correlationId", correlationId);
        }

        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            problem.setProperty("errors", fieldErrors);
        }
        return problem;
    }

    public static ProblemDetail build(ErrorCode errorCode, String detail, HttpServletRequest request) {
        return build(errorCode, detail, request, null);
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        String mdcId = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
        if (StringUtils.hasText(mdcId)) {
            return mdcId;
        }
        if (request != null) {
            Object attr = request.getAttribute(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
            if (attr instanceof String str && StringUtils.hasText(str)) {
                return str;
            }
        }
        return null;
    }
}
