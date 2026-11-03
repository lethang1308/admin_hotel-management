package com.example.hotelmanagement.config;

import com.example.hotelmanagement.modules.auth.service.AdminLoginLifecycleService;
import com.example.hotelmanagement.security.authentication.CmsSessionGuardFilter;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;
import com.example.hotelmanagement.security.authorization.SecurityConstants;
import com.example.hotelmanagement.security.jwt.JwtAuthenticationFilter;
import com.example.hotelmanagement.security.jwt.RestAccessDeniedHandler;
import com.example.hotelmanagement.security.jwt.RestAuthenticationEntryPoint;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    private final CorsConfigurationSource corsConfigurationSource;
    private final AppProperties appProperties;
    private final CmsSessionGuardFilter cmsSessionGuardFilter;
    private final AdminLoginLifecycleService loginLifecycle;

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public FilterRegistrationBean<CmsSessionGuardFilter> cmsGuardRegistration(
            CmsSessionGuardFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Prevent Spring Boot from registering JwtAuthenticationFilter twice (once in raw Servlet
     * container and once in apiSecurityFilterChain).
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Chain 1: Stateless JWT REST API security for React/mobile clients (/api/**). */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(restAuthenticationEntryPoint)
                                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/register",
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/refresh",
                                                "/api/v1/auth/logout")
                                        .permitAll()
                                        .requestMatchers("/api/v1/users/**")
                                        .hasRole(SecurityConstants.ROLE_ADMIN)
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Chain 2: Stateful Session + CSRF + Form Login security for Thymeleaf Admin & documentation.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        boolean openApiPublic = appProperties.getOpenapi().isPublicAccess();

        http.sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                        .sessionFixation(fixation -> fixation.migrateSession())
                                        .maximumSessions(-1)
                                        .sessionRegistry(sessionRegistry())
                                        .expiredUrl("/admin/login?expired=true"))
                .authorizeHttpRequests(
                        auth -> {
                            auth.requestMatchers("/", "/error", "/favicon.ico").permitAll();
                            auth.requestMatchers(
                                            "/admin/login",
                                            "/admin/assets/**",
                                            "/admin/css/**",
                                            "/admin/js/**",
                                            "/admin/images/**",
                                            "/media/hotel/**")
                                    .permitAll();
                            auth.requestMatchers("/actuator/health", "/actuator/health/**")
                                    .permitAll();
                            auth.requestMatchers("/actuator/**")
                                    .hasRole(SecurityConstants.ROLE_ADMIN);

                            if (openApiPublic) {
                                auth.requestMatchers(
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs/**")
                                        .permitAll();
                            } else {
                                auth.requestMatchers(
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs/**")
                                        .hasRole(SecurityConstants.ROLE_ADMIN);
                            }

                            auth.requestMatchers("/admin/users/**")
                                    .hasRole(SecurityConstants.ROLE_ADMIN);
                            auth.requestMatchers("/admin/**")
                                    .hasAnyRole(
                                            SecurityConstants.ROLE_ADMIN,
                                            SecurityConstants.ROLE_RECEPTIONIST);
                            auth.anyRequest().authenticated();
                        })
                .formLogin(
                        form ->
                                form.loginPage("/admin/login")
                                        .loginProcessingUrl("/admin/login")
                                        .usernameParameter("email")
                                        .passwordParameter("password")
                                        .successHandler(adminAuthenticationSuccessHandler())
                                        .failureHandler(adminAuthenticationFailureHandler())
                                        .permitAll())
                .logout(
                        logout ->
                                logout.logoutUrl("/admin/logout")
                                        .logoutSuccessUrl("/admin/login?logout=true")
                                        .invalidateHttpSession(true)
                                        .clearAuthentication(true)
                                        .deleteCookies("JSESSIONID")
                                        .addLogoutHandler(
                                                (request, response, authentication) -> {
                                                    if (authentication != null
                                                            && authentication.getPrincipal()
                                                                    instanceof
                                                                    CustomUserDetails
                                                                    details) {
                                                        loginLifecycle.logout(details.getId());
                                                    }
                                                })
                                        .permitAll())
                .addFilterBefore(cmsSessionGuardFilter, AuthorizationFilter.class);

        return http.build();
    }

    private AuthenticationSuccessHandler adminAuthenticationSuccessHandler() {
        return (request, response, authentication) -> {
            boolean isAdmin =
                    authentication.getAuthorities().stream()
                            .anyMatch(
                                    a ->
                                            ("ROLE_" + SecurityConstants.ROLE_ADMIN)
                                                    .equals(a.getAuthority()));
            boolean isReceptionist =
                    authentication.getAuthorities().stream()
                            .anyMatch(
                                    a ->
                                            ("ROLE_" + SecurityConstants.ROLE_RECEPTIONIST)
                                                    .equals(a.getAuthority()));
            if (!isAdmin && !isReceptionist) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                response.sendRedirect(request.getContextPath() + "/admin/login?accessDenied=true");
                return;
            }
            if (authentication.getPrincipal() instanceof CustomUserDetails details)
                loginLifecycle.login(details.getId());
            response.sendRedirect(
                    request.getContextPath() + (isAdmin ? "/admin" : "/admin/receptionist"));
        };
    }

    private AuthenticationFailureHandler adminAuthenticationFailureHandler() {
        return (request, response, exception) -> {
            if (exception instanceof DisabledException || exception instanceof LockedException) {
                response.sendRedirect(request.getContextPath() + "/admin/login?disabled=true");
                return;
            }
            response.sendRedirect(request.getContextPath() + "/admin/login?error=true");
        };
    }
}
