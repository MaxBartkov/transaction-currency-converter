package com.example.currencyconverter.controller.converter;

import com.example.currencyconverter.model.Currency;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

@Component
public class CurrencyConverter implements Converter<String, Currency> {
    @Override
    public Currency convert(@NonNull String source) {
        try {
            return Currency.valueOf(source);
        } catch (IllegalArgumentException exception) {
            return Currency.fromTreasuryName(source);
        }
    }
}
