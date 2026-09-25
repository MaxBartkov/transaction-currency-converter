package com.example.currencyconverter.support;

import com.example.currencyconverter.client.dto.TreasuryRate;
import com.example.currencyconverter.client.dto.TreasuryResponse;
import com.example.currencyconverter.model.Currency;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class TreasuryApiStub implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback {
    private static final String HOST = "127.0.0.1";
    private static final String BASE_URL_FORMAT = "http://%s:%d";
    private static final String ROOT_PATH = "/";
    private static final String RATES_PATH = "/v1/accounting/od/rates_of_exchange";
    private static final int DYNAMIC_PORT = 0;
    private static final int DEFAULT_BACKLOG = 0;
    private static final int STOP_DELAY_SECONDS = 0;
    private static final long NO_RESPONSE_BODY = -1;

    private static final Currency DEFAULT_CURRENCY = Currency.CANADA_DOLLAR;
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("1.37");
    private static final LocalDate DEFAULT_RATE_DATE = LocalDate.of(2024, 6, 30);
    private static final TreasuryResponse DEFAULT_RESPONSE = new TreasuryResponse(List.of(
            new TreasuryRate(DEFAULT_CURRENCY.getTreasuryName(), DEFAULT_RATE, DEFAULT_RATE_DATE, DEFAULT_RATE_DATE)));
    private static final TreasuryResponse NO_RATES_RESPONSE = new TreasuryResponse(List.of());
    private static final TreasuryResponse INVALID_RATE_RESPONSE = new TreasuryResponse(List.of(
            new TreasuryRate(null, null, null, null)));
    private static final Map<String, Object> EMPTY_ERROR_BODY = Map.of();

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS)
            .defaultPropertyInclusion(JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
            .build();

    private final AtomicReference<StubResponse> response = new AtomicReference<>(
            serializeResponse(HttpStatus.OK, DEFAULT_RESPONSE));
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastRawQuery = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService executor;

    @Override
    public void beforeAll(ExtensionContext context) throws IOException {
        server = HttpServer.create(new InetSocketAddress(HOST, DYNAMIC_PORT), DEFAULT_BACKLOG);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(ROOT_PATH, this::handle);
        server.start();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        requestCount.set(0);
        lastQuery.set(null);
        lastRawQuery.set(null);
        respond(HttpStatus.OK, DEFAULT_RESPONSE);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (server != null) {
            server.stop(STOP_DELAY_SECONDS);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public String baseUrl() {
        return BASE_URL_FORMAT.formatted(HOST, server.getAddress().getPort());
    }

    public int requestCount() {
        return requestCount.get();
    }

    public String lastQuery() {
        return lastQuery.get();
    }

    public String lastRawQuery() {
        return lastRawQuery.get();
    }

    public void respondWithNoRates() {
        respond(HttpStatus.OK, NO_RATES_RESPONSE);
    }

    public void respondWithInvalidRate() {
        respond(HttpStatus.OK, INVALID_RATE_RESPONSE);
    }

    public void respondWith(TreasuryResponse body) {
        respond(HttpStatus.OK, body);
    }

    public void respondWithRawJson(String body) {
        response.set(new StubResponse(HttpStatus.OK, body.getBytes(StandardCharsets.UTF_8), Duration.ZERO));
    }

    public void respondWithError(HttpStatus status) {
        Validate.notNull(status, "Response status must not be null");
        Validate.isTrue(status.isError(), "Response status must be a client or server error");
        respond(status, EMPTY_ERROR_BODY);
    }

    public void respondWithRate(String rate, String date) {
        respondWithRate(DEFAULT_CURRENCY, rate, date);
    }

    public void respondWithRate(Currency currency, String rate, String date) {
        respondWithRate(currency, rate, date, date);
    }

    public void respondWithRate(Currency currency, String rate, String recordDate, String effectiveDate) {
        BigDecimal exchangeRate = new BigDecimal(rate);
        TreasuryRate treasuryRate = new TreasuryRate(currency.getTreasuryName(), exchangeRate,
                LocalDate.parse(recordDate), LocalDate.parse(effectiveDate));
        TreasuryResponse treasuryResponse = new TreasuryResponse(List.of(treasuryRate));
        respond(HttpStatus.OK, treasuryResponse);
    }

    public void delayResponse(Duration duration) {
        Validate.notNull(duration, "Response delay must not be null");
        Validate.isTrue(!duration.isNegative(), "Response delay must not be negative");
        response.updateAndGet(current -> new StubResponse(current.status(), current.body(), duration));
    }

    private void respond(HttpStatus status, Object body) {
        response.set(serializeResponse(status, body));
    }

    private static StubResponse serializeResponse(HttpStatus status, Object body) {
        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(body);
            return new StubResponse(status, json, Duration.ZERO);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize Treasury stub response", exception);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String rawQuery = StringUtils.defaultString(exchange.getRequestURI().getRawQuery());
        lastRawQuery.set(rawQuery);
        lastQuery.set(URLDecoder.decode(rawQuery, StandardCharsets.UTF_8));
        StubResponse current = response.get();
        try (exchange) {
            if (!HttpMethod.GET.matches(exchange.getRequestMethod())
                    || !RATES_PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(HttpStatus.NOT_FOUND.value(), NO_RESPONSE_BODY);
                return;
            }
            Thread.sleep(current.delay());
            byte[] body = current.body();
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(current.status().value(), body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            if (current.delay().isZero()) {
                throw exception;
            }
        }
    }

    private record StubResponse(HttpStatus status, byte[] body, Duration delay) {
    }
}
