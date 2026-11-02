package com.example.hotelmanagement.security.jwt;

import com.example.hotelmanagement.common.exception.ErrorCode;
import com.example.hotelmanagement.common.exception.ProblemDetailFactory;
import com.example.hotelmanagement.common.exception.UnauthorizedException;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;
import com.example.hotelmanagement.security.authentication.CustomUserDetailsService;
import com.example.hotelmanagement.security.authorization.SecurityConstants;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final JsonMapper jsonMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
        String requestUri = request.getRequestURI() != null ? request.getRequestURI() : "";
        String path = requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : requestUri;
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader(SecurityConstants.AUTH_HEADER);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(SecurityConstants.BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = authHeader.substring(SecurityConstants.BEARER_PREFIX.length()).trim();
        try {
            JWTClaimsSet claims = jwtTokenProvider.validateAndParseClaims(rawToken);
            Long userId = claims.getLongClaim("uid");
            CustomUserDetails userDetails = userDetailsService.loadUserById(userId);

            if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
                SecurityContextHolder.clearContext();
                writeDisabledResponse(request, response);
                return;
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (UnauthorizedException ex) {
            SecurityContextHolder.clearContext();
            request.setAttribute(SecurityConstants.AUTH_ERROR_CODE_ATTR, ex.getErrorCode());
        } catch (UsernameNotFoundException | ParseException ex) {
            SecurityContextHolder.clearContext();
            request.setAttribute(SecurityConstants.AUTH_ERROR_CODE_ATTR, ErrorCode.TOKEN_INVALID);
        }

        filterChain.doFilter(request, response);
    }

    private void writeDisabledResponse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetailFactory.build(
                ErrorCode.ACCOUNT_DISABLED,
                ErrorCode.ACCOUNT_DISABLED.getDefaultMessage(),
                request
        );
        response.setStatus(ErrorCode.ACCOUNT_DISABLED.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(problem));
    }
}
