package com.example.currencyconverter.controller;

import com.example.currencyconverter.dto.ConvertedTransactionResponse;
import com.example.currencyconverter.dto.CreateTransactionRequest;
import com.example.currencyconverter.dto.TransactionResponse;
import com.example.currencyconverter.model.Currency;
import com.example.currencyconverter.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private final TransactionService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@Valid @RequestBody CreateTransactionRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/conversion")
    public ConvertedTransactionResponse convert(@PathVariable UUID id, @RequestParam Currency currency) {
        return service.convert(id, currency);
    }
}
