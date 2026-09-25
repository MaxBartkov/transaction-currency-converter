package com.example.currencyconverter.client.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "treasury")
public record TreasuryProperties(@NotNull URI baseUrl,
                                 @NotNull @DurationMin(millis = 1) Duration connectTimeout,
                                 @NotNull @DurationMin(millis = 1) Duration readTimeout) {
}
