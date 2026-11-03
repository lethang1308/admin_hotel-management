package com.example.hotelmanagement.modules.pricing.service;

public class PricingCalculationException extends RuntimeException {
    public PricingCalculationException(String messageKey) {
        super(messageKey);
    }
}
