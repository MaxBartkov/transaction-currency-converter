package com.example.currencyconverter.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

@Builder(toBuilder = true)
public record CreateTransactionRequest(
        @NotBlank @Size(max = 50)
        @Pattern(regexp = "[^\\x00]*", message = "must not contain a null character") String description,
        @NotNull LocalDate transactionDate,
        @NotNull
        @DecimalMin(value = "0.005", message = "must round to at least 0.01 USD")
        @DecimalMax("99999999999999999.99") BigDecimal amountUsd) {
}
