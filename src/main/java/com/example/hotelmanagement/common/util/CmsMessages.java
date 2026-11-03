package com.example.hotelmanagement.common.util;

import com.example.hotelmanagement.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CmsMessages {
    private final MessageSource source;

    public String text(String key, Object... args) {
        return source.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    public BusinessException error(String key) {
        return new BusinessException(text(key));
    }
}
