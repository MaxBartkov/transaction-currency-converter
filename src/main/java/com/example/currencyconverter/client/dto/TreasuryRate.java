package com.example.currencyconverter.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TreasuryRate(@JsonProperty("country_currency_desc") String currency,
                           @JsonProperty("exchange_rate") BigDecimal rate,
                           @JsonProperty("record_date") LocalDate recordDate,
                           @JsonProperty("effective_date") LocalDate effectiveDate) {
}
