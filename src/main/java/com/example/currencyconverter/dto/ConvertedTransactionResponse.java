package com.example.currencyconverter.dto;

import com.example.currencyconverter.model.Currency;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Builder
public record ConvertedTransactionResponse(UUID id, String description, LocalDate transactionDate,
                                        BigDecimal amountUsd, Currency currency, BigDecimal exchangeRate,
                                        LocalDate exchangeRateDate, LocalDate exchangeRateEffectiveDate,
                                        BigDecimal convertedAmount) {
}
