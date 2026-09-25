package com.example.currencyconverter.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(UUID id) {
        super("Transaction %s was not found".formatted(id));
    }
}
