package com.example.hotelmanagement.security.authentication;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class CmsSessionGuardFilter extends OncePerRequestFilter {
    private final CustomUserDetailsService detailsService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI().substring(request.getContextPath().length());
        return !p.startsWith("/admin")
                || p.equals("/admin/login")
                || p.startsWith("/admin/assets/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof CustomUserDetails old) {
            try {
                CustomUserDetails fresh = detailsService.loadUserById(old.getId());
                if (!fresh.isEnabled()
                        || !fresh.isAccountNonLocked()
                        || fresh.getSecurityVersion() != old.getSecurityVersion()) {
                    reject(request, response);
                    return;
                }
                var updated =
                        new UsernamePasswordAuthenticationToken(
                                fresh, null, fresh.getAuthorities());
                updated.setDetails(authentication.getDetails());
                var context = SecurityContextHolder.getContext();
                context.setAuthentication(updated);
                if (request.getSession(false) != null)
                    request.getSession(false)
                            .setAttribute(
                                    HttpSessionSecurityContextRepository
                                            .SPRING_SECURITY_CONTEXT_KEY,
                                    context);
            } catch (UsernameNotFoundException ex) {
                reject(request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        response.sendRedirect(request.getContextPath() + "/admin/login?expired=true");
    }
}
