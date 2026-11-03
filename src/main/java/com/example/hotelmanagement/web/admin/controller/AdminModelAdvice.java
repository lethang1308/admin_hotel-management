package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.config.AppProperties;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.example.hotelmanagement.web")
@RequiredArgsConstructor
public class AdminModelAdvice {

    private final AppProperties appProperties;

    @ModelAttribute
    public void populateCommonAdminAttributes(
            Model model,
            HttpServletRequest request,
            Authentication authentication
    ) {
        model.addAttribute("appName", appProperties.getName());
        model.addAttribute("currentPath", request.getRequestURI());
        model.addAttribute("currentYear", java.time.Year.now().getValue());

        Locale currentLocale = LocaleContextHolder.getLocale();
        String currentLang = (currentLocale != null && "en".equalsIgnoreCase(currentLocale.getLanguage())) ? "en" : "vi";
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
