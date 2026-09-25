package com.example.currencyconverter.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TreasuryResponse(List<TreasuryRate> data) {
}
