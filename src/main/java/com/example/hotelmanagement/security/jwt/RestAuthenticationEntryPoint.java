package com.example.hotelmanagement.security.jwt;

import com.example.hotelmanagement.common.exception.ErrorCode;
import com.example.hotelmanagement.common.exception.ProblemDetailFactory;
import com.example.hotelmanagement.security.authorization.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        Object attr = request.getAttribute(SecurityConstants.AUTH_ERROR_CODE_ATTR);
        ErrorCode errorCode = (attr instanceof ErrorCode ec) ? ec : ErrorCode.UNAUTHORIZED;

        ProblemDetail problem = ProblemDetailFactory.build(
                errorCode,
                errorCode.getDefaultMessage(),
                request
        );

        response.setStatus(errorCode.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(problem));
    }
}
