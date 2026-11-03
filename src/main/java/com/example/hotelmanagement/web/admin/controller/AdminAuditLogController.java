package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.modules.audit.service.AuditLogService;

import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Controller
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit.read')")
public class AdminAuditLogController {
    private final AuditLogService audit;

    @GetMapping
    public String list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            Model m) {
        m.addAttribute("pageData", audit.search(page, size, action, type, actor, from, to));
        m.addAttribute("action", action);
        m.addAttribute("type", type);
        m.addAttribute("actor", actor);
        m.addAttribute("from", from);
        m.addAttribute("to", to);
        return "admin/audit/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model m) {
        m.addAttribute("record", audit.get(id));
        m.addAttribute("diff", audit.diff(id));
        return "admin/audit/detail";
    }
}
