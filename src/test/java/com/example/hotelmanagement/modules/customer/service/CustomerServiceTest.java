package com.example.hotelmanagement.modules.customer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.customer.dto.CustomerForm;
import com.example.hotelmanagement.modules.customer.entity.Customer;
import com.example.hotelmanagement.modules.customer.repository.CustomerRepository;
import com.example.hotelmanagement.modules.user.entity.User;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {
    @Mock CustomerRepository repository;
    @Mock IdentityDocumentCrypto identity;
    @Mock AuditLogService audit;
    @Mock CmsMessages messages;
    @InjectMocks CustomerService service;

    @Test
    void changingGuestContactDoesNotChangeLinkedLoginEmail() {
        User linked = User.builder().email("login@example.test").build();
        Customer customer =
                Customer.builder()
                        .id(1L)
                        .customerCode("KH1")
                        .fullName("Guest")
                        .user(linked)
                        .build();
        when(repository.findById(1L)).thenReturn(Optional.of(customer));
        CustomerForm form = new CustomerForm();
        form.setFullName("Guest Updated");
        form.setEmail("contact@example.test");
        service.update(1L, form);
        assertThat(customer.getEmail()).isEqualTo("contact@example.test");
        assertThat(linked.getEmail()).isEqualTo("login@example.test");
    }

    @Test
    void documentTypeChangeRequiresAReplacementOrExplicitClear() {
        Customer customer =
                Customer.builder()
                        .id(1L)
                        .customerCode("KH1")
                        .fullName("Guest")
                        .documentType("PASSPORT")
                        .documentNumberEncrypted("existing-ciphertext")
                        .build();
        when(repository.findById(1L)).thenReturn(Optional.of(customer));
        when(messages.error("cms.error.documentReplacement"))
                .thenReturn(new BusinessException("Replacement required"));
        CustomerForm form = new CustomerForm();
        form.setFullName("Guest");
        form.setDocumentType("CCCD");
        assertThatThrownBy(() -> service.update(1L, form)).isInstanceOf(BusinessException.class);
        assertThat(customer.getDocumentNumberEncrypted()).isEqualTo("existing-ciphertext");
        verifyNoInteractions(audit);
    }
}
