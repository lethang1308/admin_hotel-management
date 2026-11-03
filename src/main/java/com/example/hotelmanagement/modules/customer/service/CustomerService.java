package com.example.hotelmanagement.modules.customer.service;

import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.audit.service.*;
import com.example.hotelmanagement.modules.customer.dto.*;
import com.example.hotelmanagement.modules.customer.entity.Customer;
import com.example.hotelmanagement.modules.customer.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerService {
    private final CustomerRepository repository;
    private final IdentityDocumentCrypto identity;
    private final AuditLogService audit;
    private final CmsMessages messages;

    @PreAuthorize("hasAuthority('customer.read')")
    public PageResponse<CustomerResponse> search(
            int page, int size, String search, String nationality) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String pattern =
                "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String hash = identity.hash(search);
        return PageResponse.from(
                repository.findAll(
                        (r, q, cb) -> {
                            var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
                            if (!term.isEmpty()) {
                                var text =
                                        cb.or(
                                                cb.like(
                                                        cb.lower(r.get("customerCode")),
                                                        pattern,
                                                        '\\'),
                                                cb.like(cb.lower(r.get("fullName")), pattern, '\\'),
                                                cb.like(cb.lower(r.get("email")), pattern, '\\'),
                                                cb.like(cb.lower(r.get("phone")), pattern, '\\'));
                                p.add(
                                        hash == null
                                                ? text
                                                : cb.or(
                                                        text,
                                                        cb.equal(
                                                                r.get("documentNumberHash"),
                                                                hash)));
                            }
                            if (nationality != null && !nationality.isBlank())
                                p.add(
                                        cb.equal(
                                                r.get("nationalityCode"),
                                                nationality.trim().toUpperCase(Locale.ROOT)));
                            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
                        },
                        PageRequest.of(
                                Math.max(0, page),
                                Math.min(100, Math.max(1, size)),
                                Sort.by(Sort.Direction.DESC, "id"))),
                this::view);
    }

    @PreAuthorize("hasAuthority('customer.read')")
    public CustomerResponse get(Long id) {
        return view(required(id));
    }

    @PreAuthorize("hasAuthority('customer.update')")
    public CustomerForm form(Long id) {
        Customer c = required(id);
        CustomerForm f = new CustomerForm();
        f.setFullName(c.getFullName());
        f.setEmail(c.getEmail());
        f.setPhone(c.getPhone());
        f.setDateOfBirth(c.getDateOfBirth());
        f.setNationalityCode(c.getNationalityCode());
        f.setAddress(c.getAddress());
        f.setNotes(c.getNotes());
        f.setDocumentType(c.getDocumentType() == null ? "" : c.getDocumentType());
        return f;
    }

    @Transactional
    @PreAuthorize("hasAuthority('customer.update')")
    public Long create(CustomerForm f) {
        Customer c =
                Customer.builder()
                        .customerCode(
                                "TMP-"
                                        + UUID.randomUUID()
                                                .toString()
                                                .replace("-", "")
                                                .substring(0, 24))
                        .build();
        apply(c, f);
        repository.saveAndFlush(c);
        c.setCustomerCode("KH" + String.format("%06d", c.getId()));
        repository.flush();
        audit.record(
                "CUSTOMER_CREATE",
                "CUSTOMER",
                c.getId(),
                null,
                AuditSnapshots.customer(c),
                f.getReason());
        return c.getId();
    }

    @Transactional
    @PreAuthorize("hasAuthority('customer.update')")
    public void update(Long id, CustomerForm f) {
        Customer c = required(id);
        var before = AuditSnapshots.customer(c);
        apply(c, f);
        audit.record(
                "CUSTOMER_UPDATE",
                "CUSTOMER",
                id,
                before,
                AuditSnapshots.customer(c),
                f.getReason());
    }

    private void apply(Customer c, CustomerForm f) {
        c.setFullName(f.getFullName().trim());
        c.setEmail(blank(f.getEmail()) ? null : f.getEmail().trim().toLowerCase(Locale.ROOT));
        c.setPhone(blank(f.getPhone()) ? null : f.getPhone().replaceAll("[ .()-]", ""));
        c.setDateOfBirth(f.getDateOfBirth());
        c.setNationalityCode(
                blank(f.getNationalityCode())
                        ? null
                        : f.getNationalityCode().toUpperCase(Locale.ROOT));
        c.setAddress(f.getAddress());
        c.setNotes(f.getNotes());
        if (f.isClearDocument()) {
            c.setDocumentType(null);
            c.setDocumentNumberEncrypted(null);
            c.setDocumentNumberHash(null);
        } else if (!blank(f.getDocumentNumber())) {
            if (blank(f.getDocumentType())) throw messages.error("cms.error.documentType");
            c.setDocumentType(f.getDocumentType());
            c.setDocumentNumberEncrypted(identity.encrypt(f.getDocumentNumber()));
            c.setDocumentNumberHash(identity.hash(f.getDocumentNumber()));
        } else if (!Objects.equals(
                blank(f.getDocumentType()) ? null : f.getDocumentType(), c.getDocumentType())) {
            if (c.getDocumentNumberEncrypted() != null)
                throw messages.error("cms.error.documentReplacement");
        }
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private Customer required(Long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    private CustomerResponse view(Customer c) {
        return new CustomerResponse(
                c.getId(),
                c.getUser() == null ? null : c.getUser().getId(),
                c.getCustomerCode(),
                c.getFullName(),
                c.getEmail(),
                c.getPhone(),
                c.getDateOfBirth(),
                c.getNationalityCode(),
                c.getAddress(),
                c.getNotes(),
                c.getDocumentType(),
                identity.masked(c.getDocumentNumberEncrypted()),
                c.getCreatedAt());
    }
}
