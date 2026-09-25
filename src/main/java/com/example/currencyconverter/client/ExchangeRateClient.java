package com.example.currencyconverter.client;

import com.example.currencyconverter.client.dto.ExchangeRate;
import com.example.currencyconverter.model.Currency;

import java.time.LocalDate;
import java.util.Optional;

public interface ExchangeRateClient {
    Optional<ExchangeRate> findLatest(Currency currency, LocalDate earliestDate, LocalDate transactionDate);
}
