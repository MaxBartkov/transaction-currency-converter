package com.example.currencyconverter.client.dto;

import com.example.currencyconverter.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExchangeRate(Currency currency, BigDecimal rate, LocalDate recordDate, LocalDate effectiveDate) {
}
