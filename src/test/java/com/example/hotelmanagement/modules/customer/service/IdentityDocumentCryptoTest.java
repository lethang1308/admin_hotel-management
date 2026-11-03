package com.example.hotelmanagement.modules.customer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.util.CmsMessages;

import org.junit.jupiter.api.Test;

class IdentityDocumentCryptoTest {
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void randomNoncesProduceDifferentCiphertextWhileExactSearchIsStable() {
        var crypto = new IdentityDocumentCrypto(KEY, mock(CmsMessages.class));
        String one = crypto.encrypt("123456789012"), two = crypto.encrypt("123456789012");
        assertThat(one).isNotEqualTo(two).doesNotContain("123456789012");
        assertThat(crypto.hash("1234-5678 9012")).isEqualTo(crypto.hash("123456789012"));
        assertThat(crypto.masked(one)).endsWith("9012").doesNotContain("12345678");
    }

    @Test
    void missingKeyCannotSilentlyStorePlaintext() {
        var messages = mock(CmsMessages.class);
        when(messages.error("cms.error.identityKey"))
                .thenReturn(new BusinessException("Missing key"));
        var crypto = new IdentityDocumentCrypto("", messages);
        assertThatThrownBy(() -> crypto.encrypt("123456789012"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void invalidKeyIsRejected() {
        assertThatThrownBy(() -> new IdentityDocumentCrypto("not-base64", mock(CmsMessages.class)))
                .isInstanceOf(IllegalStateException.class);
    }
}
