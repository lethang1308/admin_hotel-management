package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.booking.OperationsQueries.Page;
import com.example.hotelmanagement.modules.booking.OperationsStore;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
class OperationsWeb {
    private final CmsMessages messages;
    private final OperationsStore db;

    void mutate(Runnable action, RedirectAttributes flash) {
        try {
            retry(
                    () -> {
                        action.run();
                        return null;
                    });
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException error) {
            flash.addFlashAttribute("errorMessage", error.getMessage());
        } catch (DataIntegrityViolationException error) {
            flash.addFlashAttribute("errorMessage", messages.text("ops.error.concurrent"));
        } catch (PessimisticLockingFailureException error) {
            flash.addFlashAttribute("errorMessage", messages.text("ops.error.retry"));
        }
    }

    <T> T retry(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException failure) {
                if (attempt == 2) throw failure;
            }
        }
    }

    LocalDate date(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw messages.error("ops.error.dates");
        }
    }

    void page(Page page, HttpServletRequest request, Model model) {
        model.addAttribute("page", page);
        var uri = UriComponentsBuilder.fromPath(request.getRequestURI());
        request.getParameterMap()
                .forEach(
                        (key, values) -> {
                            if (!key.equals("page"))
                                for (String value : values) uri.queryParam(key, value);
                        });
        if (page.previous())
            model.addAttribute(
                    "previous",
                    uri.cloneBuilder()
                            .queryParam("page", page.index() - 1)
                            .build()
                            .encode()
                            .toUriString());
        if (page.next())
            model.addAttribute(
                    "next",
                    uri.cloneBuilder()
                            .queryParam("page", page.index() + 1)
                            .build()
                            .encode()
                            .toUriString());
    }

    void types(Model model) {
        model.addAttribute(
                "types", db.rows("SELECT id,name,is_active FROM room_types ORDER BY name"));
    }

    void key(Model model) {
        model.addAttribute("requestKey", OperationsStore.code(""));
    }
}
