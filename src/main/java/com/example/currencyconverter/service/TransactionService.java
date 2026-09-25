package com.example.currencyconverter.service;

import com.example.currencyconverter.client.ExchangeRateClient;
import com.example.currencyconverter.client.dto.ExchangeRate;
import com.example.currencyconverter.dto.ConvertedTransactionResponse;
import com.example.currencyconverter.dto.CreateTransactionRequest;
import com.example.currencyconverter.dto.TransactionResponse;
import com.example.currencyconverter.exception.ExchangeRateNotFoundException;
import com.example.currencyconverter.exception.TransactionNotFoundException;
import com.example.currencyconverter.model.Currency;
import com.example.currencyconverter.model.Transaction;
import com.example.currencyconverter.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {
    private final TransactionRepository repository;
    private final ExchangeRateClient exchangeRateClient;

    public TransactionResponse create(CreateTransactionRequest request) {
        log.debug("Creating transaction");
        Transaction transaction = new Transaction(request.description(), request.transactionDate(),
                request.amountUsd().setScale(2, RoundingMode.HALF_UP));
        Transaction saved = repository.save(transaction);
        log.info("Transaction created: id={}", saved.getId());
        return TransactionResponse.from(saved);
    }

    public TransactionResponse get(UUID id) {
        log.debug("Retrieving transaction: id={}", id);
        TransactionResponse response = TransactionResponse.from(find(id));
        log.debug("Transaction retrieved: id={}", id);
        return response;
    }

    public ConvertedTransactionResponse convert(UUID id, Currency currency) {
        log.debug("Converting transaction: id={}, currency={}", id, currency);
        Transaction transaction = find(id);
        ExchangeRate rate = exchangeRateClient.findLatest(currency, transaction.getTransactionDate().minusMonths(6),
                        transaction.getTransactionDate())
                .orElseThrow(() -> {
                    log.warn("No eligible exchange rate: transactionId={}, currency={}, transactionDate={}",
                            id, currency, transaction.getTransactionDate());
                    return new ExchangeRateNotFoundException(currency, transaction.getTransactionDate());
                });
        ConvertedTransactionResponse response = ConvertedTransactionResponse.builder()
                .id(transaction.getId())
                .description(transaction.getDescription())
                .transactionDate(transaction.getTransactionDate())
                .amountUsd(transaction.getAmountUsd())
                .currency(rate.currency())
                .exchangeRate(rate.rate())
                .exchangeRateDate(rate.recordDate())
                .exchangeRateEffectiveDate(rate.effectiveDate())
                .convertedAmount(transaction.getAmountUsd().multiply(rate.rate()).setScale(2, RoundingMode.HALF_UP))
                .build();
        log.info("Transaction converted: id={}, currency={}, rateDate={}, effectiveDate={}",
                id, currency, rate.recordDate(), rate.effectiveDate());
        return response;
    }

    private Transaction find(UUID id) {
        return repository.findById(id).orElseThrow(() -> {
            log.warn("Transaction not found: id={}", id);
            return new TransactionNotFoundException(id);
        });
    }
}
