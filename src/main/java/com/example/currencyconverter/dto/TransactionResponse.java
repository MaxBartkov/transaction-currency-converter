package com.example.currencyconverter.dto;

import com.example.currencyconverter.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponse(UUID id, String description, LocalDate transactionDate, BigDecimal amountUsd) {
    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getDescription(),
                transaction.getTransactionDate(), transaction.getAmountUsd());
    }
}
