package com.example.currencyconverter.client;

import com.example.currencyconverter.client.dto.ExchangeRate;
import com.example.currencyconverter.client.dto.TreasuryRate;
import com.example.currencyconverter.client.dto.TreasuryResponse;
import com.example.currencyconverter.client.exception.TreasuryException;
import com.example.currencyconverter.model.Currency;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TreasuryExchangeRateClient implements ExchangeRateClient {
    private static final String RATES_PATH = "/v1/accounting/od/rates_of_exchange";
    private static final String RATE_FIELDS = "country_currency_desc,exchange_rate,record_date,effective_date";
    private static final String RATE_SORT = "-record_date,-effective_date";
    private static final String RATE_FILTER =
            "country_currency_desc:eq:%s,record_date:gte:%s,record_date:lte:%s,effective_date:lte:%s";
    private static final int PAGE_SIZE = 1;

    private final RestClient client;

    @Override
    public Optional<ExchangeRate> findLatest(Currency currency, LocalDate earliestDate, LocalDate transactionDate) {
        TreasuryResponse response = fetchRates(currency, earliestDate, transactionDate);
        if (response == null || response.data() == null) {
            throw TreasuryException.invalidResponse("Treasury response is missing the data array");
        }
        if (response.data().size() > PAGE_SIZE) {
            throw TreasuryException.invalidResponse("Treasury returned more records than the requested page size");
        }
        if (response.data().isEmpty()) {
            return Optional.empty();
        }

        TreasuryRate rate = response.data().getFirst();
        validateRate(rate, currency, earliestDate, transactionDate);
        return Optional.of(new ExchangeRate(currency, rate.rate(), rate.recordDate(), rate.effectiveDate()));
    }

    private TreasuryResponse fetchRates(Currency currency, LocalDate earliestDate, LocalDate transactionDate) {
        String filter = RATE_FILTER.formatted(
                currency.getTreasuryName(), earliestDate, transactionDate, transactionDate);
        try {
            return client.get()
                    .uri(builder -> builder.path(RATES_PATH)
                            .queryParam("fields", RATE_FIELDS)
                            .queryParam("filter", "{filter}")
                            .queryParam("sort", RATE_SORT)
                            .queryParam("page[size]", PAGE_SIZE)
                            .build(filter))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(TreasuryResponse.class);
        } catch (ResourceAccessException exception) {
            throw TreasuryException.unavailable(exception);
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            if (status.is5xxServerError() || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                throw TreasuryException.unavailable(exception);
            }
            throw TreasuryException.invalidResponse("Treasury returned HTTP " + status.value(), exception);
        } catch (RestClientException exception) {
            throw TreasuryException.invalidResponse("Cannot decode Treasury response", exception);
        }
    }

    private void validateRate(TreasuryRate rate, Currency currency, LocalDate earliestDate, LocalDate transactionDate) {
        if (rate == null) {
            throw TreasuryException.invalidResponse("Treasury data array contains a null record");
        }
        if (!currency.getTreasuryName().equals(rate.currency())) {
            throw TreasuryException.invalidResponse("Treasury currency does not match " + currency.name());
        }
        if (rate.rate() == null || rate.rate().signum() <= 0) {
            throw TreasuryException.invalidResponse("Treasury exchange_rate must be present and positive");
        }
        if (rate.recordDate() == null) {
            throw TreasuryException.invalidResponse("Treasury record_date is missing");
        }
        if (rate.recordDate().isBefore(earliestDate) || rate.recordDate().isAfter(transactionDate)) {
            throw TreasuryException.invalidResponse("Treasury record_date is outside the requested interval");
        }
        if (rate.effectiveDate() == null) {
            throw TreasuryException.invalidResponse("Treasury effective_date is missing");
        }
        if (rate.effectiveDate().isAfter(transactionDate)) {
            throw TreasuryException.invalidResponse("Treasury effective_date is after the transaction date");
        }
    }
}
