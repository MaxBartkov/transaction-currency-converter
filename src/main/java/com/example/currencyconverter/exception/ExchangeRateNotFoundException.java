package com.example.currencyconverter.exception;

import com.example.currencyconverter.model.Currency;

import java.time.LocalDate;

public class ExchangeRateNotFoundException extends RuntimeException {
    public ExchangeRateNotFoundException(Currency currency, LocalDate date) {
        super("Transaction cannot be converted to %s: no exchange rate between %s and %s (inclusive)"
                .formatted(currency.getTreasuryName(), date.minusMonths(6), date));
    }
}
