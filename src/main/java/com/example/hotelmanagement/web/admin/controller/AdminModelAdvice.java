package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.config.AppProperties;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Locale;

@ControllerAdvice(basePackages = "com.example.hotelmanagement.web")
@RequiredArgsConstructor
public class AdminModelAdvice {

    private final AppProperties appProperties;
    private final com.example.hotelmanagement.modules.hotel.service.HotelSettingService
            hotelSettings;

    @ModelAttribute
    public void populateCommonAdminAttributes(
            Model model, HttpServletRequest request, Authentication authentication) {
        var brand = hotelSettings.brand();
        model.addAttribute(
                "appName",
                brand == null || brand.name() == null ? appProperties.getName() : brand.name());
        model.addAttribute("hotelLogo", brand == null ? null : brand.logoUrl());
        model.addAttribute("currentPath", request.getRequestURI());
        var languageUrl =
                org.springframework.web.util.UriComponentsBuilder.fromPath(request.getRequestURI())
                        .query("GET".equals(request.getMethod()) ? request.getQueryString() : null);
        model.addAttribute(
                "langViUrl",
                languageUrl.cloneBuilder().replaceQueryParam("lang", "vi").build().toUriString());
        model.addAttribute(
                "langEnUrl",
                languageUrl.cloneBuilder().replaceQueryParam("lang", "en").build().toUriString());
        model.addAttribute("currentYear", java.time.Year.now().getValue());

        Locale currentLocale = LocaleContextHolder.getLocale();
        String currentLang =
                (currentLocale != null && "en".equalsIgnoreCase(currentLocale.getLanguage()))
                        ? "en"
                        : "vi";
        model.addAttribute("currentLang", currentLang);

        if (authentication != null && authentication.isAuthenticated()) {
            model.addAttribute("currentAdminEmail", authentication.getName());
            if (authentication.getPrincipal() instanceof CustomUserDetails details) {
                model.addAttribute("currentAdminName", details.getFullName());
            } else {
                model.addAttribute("currentAdminName", authentication.getName());
            }
        }
    }
}
