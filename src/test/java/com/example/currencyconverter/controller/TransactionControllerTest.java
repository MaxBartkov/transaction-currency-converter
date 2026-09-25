package com.example.currencyconverter.controller;

import com.example.currencyconverter.client.dto.TreasuryRate;
import com.example.currencyconverter.client.dto.TreasuryResponse;
import com.example.currencyconverter.dto.ConvertedTransactionResponse;
import com.example.currencyconverter.dto.CreateTransactionRequest;
import com.example.currencyconverter.dto.TransactionResponse;
import com.example.currencyconverter.exception.ErrorCode;
import com.example.currencyconverter.model.Currency;
import com.example.currencyconverter.repository.TransactionRepository;
import com.example.currencyconverter.support.TreasuryApiStub;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionControllerTest {
    private static final String BASE = "/api/v1/transactions";
    private static final CreateTransactionRequest VALID_REQUEST = CreateTransactionRequest.builder()
            .description("Lunch")
            .transactionDate(LocalDate.of(2024, 6, 30))
            .amountUsd(new BigDecimal("10.005"))
            .build();

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @RegisterExtension
    static final TreasuryApiStub TREASURY = new TreasuryApiStub();

    @Autowired
    private TestRestTemplate http;

    @MockitoSpyBean
    private TransactionRepository repository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("treasury.base-url", TREASURY::baseUrl);
        registry.add("treasury.connect-timeout", () -> "500ms");
        registry.add("treasury.read-timeout", () -> "500ms");
    }

    @Test
    void exposesSwaggerUiAndItsConfiguration() {
        var ui = http.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getBody()).contains("Swagger UI", "swagger-ui-bundle.js");

        var config = http.getForEntity("/v3/api-docs/swagger-config", JsonNode.class);
        assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertNotNull(config.getBody());
        assertThat(config.getBody().path("url").asText()).isEqualTo("/v3/api-docs");
    }

    @Test
    void exposesOpenApiWithTransactionValidationCurrenciesAndErrors() {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode api = response.getBody();
        Assertions.assertNotNull(api);
        assertThat(api.path("openapi").asText()).startsWith("3.0.");
        assertThat(api.path("info").path("title").asText()).isEqualTo("Transaction Currency Converter");
        assertThat(api.path("servers").get(0).path("url").asText()).isEqualTo("/");
        assertThat(api.path("paths").size()).isEqualTo(3);
        JsonNode paths = api.path("paths");
        assertThat(paths.path(BASE).path("post").path("responses").has("201")).isTrue();
        assertThat(paths.path(BASE + "/{id}").path("get").path("responses").has("404")).isTrue();
        JsonNode conversion = paths.path(BASE + "/{id}/conversion").path("get");
        assertThat(conversion.path("responses").properties()).extracting(Map.Entry::getKey)
                .contains("200", "400", "404", "422", "500", "502", "503");
        JsonNode currency = conversion.path("parameters").findParents("name").stream()
                .filter(parameter -> parameter.path("name").asText().equals("currency"))
                .findFirst().orElseThrow();
        assertThat(currency.path("required").asBoolean()).isTrue();
        assertThat(currency.path("schema").path("oneOf").size()).isEqualTo(2);

        JsonNode schemas = api.path("components").path("schemas");
        assertThat(schemas.path("CurrencyCode").path("enum").valueStream().map(JsonNode::asText))
                .containsExactly(Arrays.stream(Currency.values()).map(Enum::name).toArray(String[]::new));
        assertThat(schemas.path("Currency").path("enum").valueStream().map(JsonNode::asText))
                .containsExactly(Arrays.stream(Currency.values()).map(Currency::getTreasuryName).toArray(String[]::new));
        JsonNode request = schemas.path("CreateTransactionRequest");
        assertThat(request.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(request.path("properties").path("description").path("maxLength").asInt()).isEqualTo(50);
        assertThat(request.path("properties").path("amountUsd").path("minimum").decimalValue())
                .isEqualByComparingTo("0.005");
        assertThat(http.getForObject("/v3/api-docs", String.class))
                .containsPattern("\"maximum\"\\s*:\\s*99999999999999999\\.99");
    }

    @Test
    void swaggerExampleCreatesAValidTransaction() {
        var documentation = http.getForEntity("/v3/api-docs", JsonNode.class);
        Assertions.assertNotNull(documentation.getBody());
        JsonNode example = documentation.getBody().path("paths").path(BASE).path("post")
                .path("requestBody").path("content").path(MediaType.APPLICATION_JSON_VALUE).path("example");
        assertThat(example.path("transactionDate").asText()).isEqualTo("2024-06-30");

        var response = http.postForEntity(BASE, example, TransactionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().transactionDate()).isEqualTo(VALID_REQUEST.transactionDate());
        assertThat(response.getBody().amountUsd()).isEqualByComparingTo("10.01");
    }

    @Test
    void exposesOpenApiAsYaml() {
        var response = http.getForEntity("/v3/api-docs.yaml", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("openapi: 3.0.", "title: Transaction Currency Converter",
                "/api/v1/transactions:", "/api/v1/transactions/{id}:", "/api/v1/transactions/{id}/conversion:");
    }

    @Test
    void createsPersistsReadsAndConvertsTransaction() {
        var created = create(VALID_REQUEST);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode transaction = created.getBody();
        Assertions.assertNotNull(transaction);
        String id = transaction.path("id").asText();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(transaction.path("amountUsd").decimalValue()).isEqualByComparingTo("10.01");

        var loaded = http.getForEntity(BASE + "/" + id, JsonNode.class);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).isEqualTo(transaction);

        var converted = convert(id, Currency.CANADA_DOLLAR);
        assertThat(converted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode result = converted.getBody();
        Assertions.assertNotNull(result);
        assertThat(result.path("id").asText()).isEqualTo(id);
        assertThat(result.path("description").asText()).isEqualTo("Lunch");
        assertThat(result.path("transactionDate").asText()).isEqualTo("2024-06-30");
        assertThat(result.path("amountUsd").decimalValue()).isEqualByComparingTo("10.01");
        assertThat(result.path("currency").asText()).isEqualTo(Currency.CANADA_DOLLAR.getTreasuryName());
        assertThat(result.path("exchangeRate").decimalValue()).isEqualByComparingTo("1.37");
        assertThat(result.path("convertedAmount").decimalValue()).isEqualByComparingTo("13.71");
        assertThat(result.path("exchangeRateDate").asText()).isEqualTo("2024-06-30");
        assertThat(convertByTreasuryName(id, Currency.CANADA_DOLLAR).getBody()).isEqualTo(result);

        var second = create(VALID_REQUEST);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Assertions.assertNotNull(second.getBody());
        assertThat(second.getBody().path("id")).isNotEqualTo(transaction.path("id"));
    }

    @Test
    void noEligibleRateReturns422AndPreservesTransaction() {
        String id = createTransaction(VALID_REQUEST);
        TREASURY.respondWithNoRates();
        var converted = convert(id, Currency.CANADA_DOLLAR);
        assertProblem(converted, HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.EXCHANGE_RATE_NOT_FOUND);
        Assertions.assertNotNull(converted.getBody());
        assertThat(converted.getBody().path("detail").asText()).contains("cannot be converted");
        assertThat(http.getForEntity(BASE + "/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"TOO_MANY_REQUESTS", "INTERNAL_SERVER_ERROR", "SERVICE_UNAVAILABLE"})
    void treasuryOutageReturns503(HttpStatus status) {
        String id = createTransaction(VALID_REQUEST);
        TREASURY.respondWithError(status);
        var converted = convert(id, Currency.CANADA_DOLLAR);
        assertProblem(converted, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.TREASURY_UNAVAILABLE);
    }

    @Test
    void corruptTreasuryResponseReturns502() {
        String id = createTransaction(VALID_REQUEST);
        TREASURY.respondWithInvalidRate();
        var converted = convert(id, Currency.CANADA_DOLLAR);
        assertProblem(converted, HttpStatus.BAD_GATEWAY, ErrorCode.TREASURY_INVALID_RESPONSE);
    }

    @Test
    void missingTransactionReturns404WithoutTreasuryCall() {
        String id = UUID.randomUUID().toString();
        assertProblem(http.getForEntity(BASE + "/" + id, JsonNode.class), HttpStatus.NOT_FOUND, ErrorCode.TRANSACTION_NOT_FOUND);
        assertProblem(convert(id, Currency.CANADA_DOLLAR), HttpStatus.NOT_FOUND, ErrorCode.TRANSACTION_NOT_FOUND);
        assertThat(TREASURY.requestCount()).isZero();
    }

    @Test
    void unsupportedCurrencyReturns400WithoutTreasuryCall() {
        String id = createTransaction(VALID_REQUEST);
        var response = http.getForEntity(BASE + "/{id}/conversion?currency={currency}",
                JsonNode.class, id, "Unknown-Currency");
        assertProblem(response, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        assertThat(TREASURY.requestCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-02-29", "2024-08-31"})
    void convertsAtBothInclusiveDateBoundaries(String rateDate) {
        String id = createTransaction(VALID_REQUEST.toBuilder()
                .transactionDate(LocalDate.of(2024, 8, 31))
                .build());
        TREASURY.respondWithRate("1.005", rateDate);

        var response = convert(id, Currency.CANADA_DOLLAR);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().path("convertedAmount").decimalValue()).isEqualByComparingTo("10.06");
        assertThat(response.getBody().path("exchangeRateDate").asText()).isEqualTo(rateDate);
        assertThat(TREASURY.lastQuery()).contains("record_date:gte:2024-02-29", "record_date:lte:2024-08-31",
                "effective_date:lte:2024-08-31", "sort=-record_date,-effective_date", "page[size]=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-02-28", "2024-09-01"})
    void refusesOutOfWindowRatesReturnedByTreasury(String rateDate) {
        String id = createTransaction(VALID_REQUEST.toBuilder()
                .transactionDate(LocalDate.of(2024, 8, 31))
                .build());
        TREASURY.respondWithRate("1.37", rateDate);
        assertProblem(convert(id, Currency.CANADA_DOLLAR), HttpStatus.BAD_GATEWAY, ErrorCode.TREASURY_INVALID_RESPONSE);
    }

    @Test
    void treasuryTimeoutReturns503WithoutLosingTransaction() {
        String id = createTransaction(VALID_REQUEST);
        TREASURY.delayResponse(Duration.ofSeconds(2));
        assertProblem(convert(id, Currency.CANADA_DOLLAR), HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.TREASURY_UNAVAILABLE);
        assertThat(http.getForEntity(BASE + "/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unexpectedDatabaseFailureReturnsGeneric500() {
        UUID id = UUID.fromString(createTransaction(VALID_REQUEST));
        doThrow(new DataAccessResourceFailureException("Internal connection details"))
                .when(repository).findById(id);

        var response = http.getForEntity(BASE + "/" + id, JsonNode.class);

        assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().path("detail").asText()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().toString()).doesNotContain("Internal connection details");
    }

    @Test
    void invalidIdAndMissingCurrencyReturn400() {
        assertProblem(http.getForEntity(BASE + "/not-a-uuid", JsonNode.class), HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        assertProblem(http.getForEntity(BASE + "/" + UUID.randomUUID() + "/conversion", JsonNode.class),
                HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        assertThat(TREASURY.requestCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidInputReturns400(Object body, ErrorCode code) {
        var response = create(body);
        assertProblem(response, HttpStatus.BAD_REQUEST, code);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().has("id")).isFalse();
        assertThat(TREASURY.requestCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"1.004,1.00", "1.005,1.01"})
    void roundsConvertedAmountsThroughTheController(String rate, String expected) {
        String id = createTransaction(VALID_REQUEST.toBuilder()
                .amountUsd(new BigDecimal("1.00"))
                .build());
        TREASURY.respondWithRate(rate, "2024-06-30");

        var response = convert(id, Currency.CANADA_DOLLAR);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().path("amountUsd").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(response.getBody().path("convertedAmount").decimalValue()).isEqualByComparingTo(expected);
    }

    @Test
    void convertsCurrencyNamesContainingSpacesAndAmpersands() {
        Currency currency = Currency.SAO_TOME_PRINCIPE_DOBRAS;
        String id = createTransaction(VALID_REQUEST);
        TREASURY.respondWithRate(currency, "1.37", "2024-06-30");

        var response = convertByTreasuryName(id, currency);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertNotNull(response.getBody());
        assertThat(response.getBody().path("currency").asText()).isEqualTo(currency.getTreasuryName());
        assertThat(response.getBody().path("convertedAmount").decimalValue()).isEqualByComparingTo("13.71");
        assertThat(TREASURY.lastQuery()).contains("country_currency_desc:eq:" + currency.getTreasuryName());
        assertThat(TREASURY.lastRawQuery()).contains("%26").doesNotContain(" ");
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(VALID_REQUEST.toBuilder().amountUsd(BigDecimal.ZERO).build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().amountUsd(new BigDecimal("-1")).build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().amountUsd(new BigDecimal("0.004")).build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().description("x".repeat(51)).build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().description(" ").build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().description("Lunch\0").build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().description(null).build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().transactionDate(null).build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().amountUsd(null).build(), ErrorCode.VALIDATION_ERROR),
                Arguments.of(VALID_REQUEST.toBuilder().amountUsd(new BigDecimal("99999999999999999.991")).build(),
                        ErrorCode.VALIDATION_ERROR),
                Arguments.of("""
                        {"description":"Lunch","transactionDate":"2024-02-30","amountUsd":10.005}
                        """, ErrorCode.INVALID_REQUEST),
                Arguments.of("{}", ErrorCode.VALIDATION_ERROR),
                Arguments.of("{", ErrorCode.INVALID_REQUEST));
    }

    @ParameterizedTest
    @MethodSource("invalidJsonRequests")
    void rejectsInvalidJsonTypesAndStructure(String body) {
        assertProblem(create(body), HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        assertThat(TREASURY.requestCount()).isZero();
    }

    static Stream<String> invalidJsonRequests() {
        return Stream.of(
                """
                {"description":123,"transactionDate":"2024-06-30","amountUsd":10.005}
                """,
                """
                {"description":true,"transactionDate":"2024-06-30","amountUsd":10.005}
                """,
                """
                {"description":"Lunch","transactionDate":"2024-06-30","amountUsd":"10.005"}
                """,
                """
                {"description":"Lunch","transactionDate":"2024-06-30","amountUsd":true}
                """,
                """
                {"description":"Lunch","transactionDate":[2024,6,30],"amountUsd":10.005}
                """,
                """
                {"description":"Lunch","transactionDate":1719705600000,"amountUsd":10.005}
                """,
                """
                {"description":"Lunch","transactionDate":"2024-06-30","amountUsd":10.005,"unknown":1}
                """,
                """
                {"description":"Lunch","transactionDate":"2024-06-30","amountUsd":10.005} {"amountUsd":999}
                """,
                """
                {"description":"Lunch","transactionDate":"2024-06-30","amountUsd":1,"amountUsd":999}
                """,
                "[]", "null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/vnd.transaction+json"})
    void enforcesStrictTypesForJsonMediaTypes(String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        String body = """
                {"description":123,"transactionDate":"2024-06-30","amountUsd":10.005}
                """;
        var response = http.postForEntity(BASE, new HttpEntity<>(body, headers), JsonNode.class);
        assertProblem(response, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsUnsupportedContentType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        var response = http.postForEntity(BASE, new HttpEntity<>("{}", headers), JsonNode.class);
        assertProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-01-01", "-0001-01-01", "+10000000-06-30", "-999999999-01-01",
            "2024-02-30", "2023-02-29", "2024-6-30", "2024-06-30T00:00:00", " 2024-06-30", ""})
    void rejectsInvalidDatesBeforePersistence(String date) {
        String body = """
                {"description":"Lunch","transactionDate":"%s","amountUsd":10.005}
                """.formatted(date);
        assertProblem(create(body), HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        assertThat(TREASURY.requestCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0001-01-01", "1582-10-04", "1582-10-10", "1582-10-15", "2024-02-29", "9999-12-31"})
    void preservesValidDatesThroughPersistence(String date) {
        String id = createTransaction(VALID_REQUEST.toBuilder().transactionDate(LocalDate.parse(date)).build());
        var loaded = http.getForEntity(BASE + "/" + id, TransactionResponse.class);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).isNotNull();
        assertThat(loaded.getBody().transactionDate()).isEqualTo(LocalDate.parse(date));
    }

    @ParameterizedTest
    @CsvSource({"0.005,0.01", "1.004,1.00", "1.005,1.01"})
    void roundsStoredAmounts(String input, String expected) {
        String id = createTransaction(VALID_REQUEST.toBuilder().amountUsd(new BigDecimal(input)).build());
        var loaded = http.getForEntity(BASE + "/" + id, TransactionResponse.class);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).isNotNull();
        assertThat(loaded.getBody().amountUsd()).isEqualByComparingTo(expected);
    }

    @Test
    void preservesAndConvertsMaximumAmountWithoutPrecisionLoss() {
        String id = createTransaction(VALID_REQUEST.toBuilder()
                .amountUsd(new BigDecimal("99999999999999999.99"))
                .build());
        var loaded = http.getForEntity(BASE + "/" + id, TransactionResponse.class);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).isNotNull();
        assertThat(loaded.getBody().amountUsd()).isEqualByComparingTo("99999999999999999.99");

        var converted = http.getForEntity(BASE + "/{id}/conversion?currency={currency}",
                ConvertedTransactionResponse.class, id, Currency.CANADA_DOLLAR.name());
        assertThat(converted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(converted.getBody()).isNotNull();
        assertThat(converted.getBody().amountUsd()).isEqualByComparingTo("99999999999999999.99");
        assertThat(converted.getBody().convertedAmount()).isEqualByComparingTo("136999999999999999.99");
    }

    @Test
    void preservesDescriptionAtMaximumLength() {
        String description = " " + "x".repeat(48) + " ";
        String id = createTransaction(VALID_REQUEST.toBuilder().description(description).build());
        var loaded = http.getForEntity(BASE + "/" + id, TransactionResponse.class);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).isNotNull();
        assertThat(loaded.getBody().description()).isEqualTo(description);
    }

    @Test
    void validationErrorsIdentifyInvalidFields() {
        var response = create(VALID_REQUEST.toBuilder().description("Lunch\0").build());
        JsonNode problem = assertProblem(response, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR);
        assertThat(problem.path("errors").path(0).path("field").asText()).isEqualTo("description");
    }

    @Test
    void returnsRecordAndEffectiveDatesIndependently() {
        String id = createTransaction(VALID_REQUEST);
        TREASURY.respondWithRate(Currency.CANADA_DOLLAR, "1.37", "2024-03-31", "2024-06-30");
        var response = convert(id, Currency.CANADA_DOLLAR);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("exchangeRateDate").asText()).isEqualTo("2024-03-31");
        assertThat(response.getBody().path("exchangeRateEffectiveDate").asText()).isEqualTo("2024-06-30");
    }

    @ParameterizedTest
    @MethodSource("invalidTreasuryResponses")
    void rejectsInvalidTreasuryRecords(TreasuryResponse body) {
        String id = createTransaction(VALID_REQUEST);
        TREASURY.respondWith(body);
        var response = convert(id, Currency.CANADA_DOLLAR);
        JsonNode problem = assertProblem(response, HttpStatus.BAD_GATEWAY, ErrorCode.TREASURY_INVALID_RESPONSE);
        assertThat(problem.path("detail").asText())
                .isEqualTo("Treasury exchange rate service returned an invalid response");
        assertThat(http.getForEntity(BASE + "/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    static Stream<TreasuryResponse> invalidTreasuryResponses() {
        String currency = Currency.CANADA_DOLLAR.getTreasuryName();
        BigDecimal rate = new BigDecimal("1.37");
        LocalDate date = VALID_REQUEST.transactionDate();
        TreasuryRate valid = new TreasuryRate(currency, rate, date, date);
        return Stream.concat(Stream.of(
                new TreasuryResponse(null),
                new TreasuryResponse(Collections.singletonList(null)),
                new TreasuryResponse(List.of(valid, valid))),
                Stream.of(
                        new TreasuryRate(null, rate, date, date),
                        new TreasuryRate(Currency.AUSTRALIA_DOLLAR.getTreasuryName(), rate, date, date),
                        new TreasuryRate(currency, null, date, date),
                        new TreasuryRate(currency, BigDecimal.ZERO, date, date),
                        new TreasuryRate(currency, new BigDecimal("-1"), date, date),
                        new TreasuryRate(currency, rate, null, date),
                        new TreasuryRate(currency, rate, date, null),
                        new TreasuryRate(currency, rate, date, date.plusDays(1)))
                        .map(record -> new TreasuryResponse(List.of(record))));
    }

    @ParameterizedTest
    @MethodSource("malformedTreasuryResponses")
    void rejectsMalformedTreasuryJson(String body) {
        String id = createTransaction(VALID_REQUEST);
        TREASURY.respondWithRawJson(body);
        assertProblem(convert(id, Currency.CANADA_DOLLAR), HttpStatus.BAD_GATEWAY, ErrorCode.TREASURY_INVALID_RESPONSE);
    }

    static Stream<String> malformedTreasuryResponses() {
        String currency = Currency.CANADA_DOLLAR.getTreasuryName();
        return Stream.of("{", "null", "{}", "{\"data\":{}}",
                """
                {"data":[{"country_currency_desc":"%s","exchange_rate":"not-a-number",
                "record_date":"2024-06-30","effective_date":"2024-06-30"}]}
                """.formatted(currency),
                """
                {"data":[{"country_currency_desc":"%s","exchange_rate":"1.37",
                "record_date":"2024-02-30","effective_date":"2024-06-30"}]}
                """.formatted(currency));
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNAUTHORIZED", "NOT_FOUND"})
    void unexpectedTreasuryRejectionReturns502(HttpStatus status) {
        String id = createTransaction(VALID_REQUEST);
        TREASURY.respondWithError(status);
        assertProblem(convert(id, Currency.CANADA_DOLLAR), HttpStatus.BAD_GATEWAY, ErrorCode.TREASURY_INVALID_RESPONSE);
    }

    private ResponseEntity<JsonNode> create(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(BASE, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String createTransaction(CreateTransactionRequest request) {
        var response = create(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Assertions.assertNotNull(response.getBody());
        return response.getBody().path("id").asText();
    }

    private ResponseEntity<JsonNode> convert(String id, Currency currency) {
        return http.getForEntity(BASE + "/{id}/conversion?currency={currency}", JsonNode.class, id, currency.name());
    }

    private ResponseEntity<JsonNode> convertByTreasuryName(String id, Currency currency) {
        return http.getForEntity(BASE + "/{id}/conversion?currency={currency}",
                JsonNode.class, id, currency.getTreasuryName());
    }

    private JsonNode assertProblem(ResponseEntity<JsonNode> response, HttpStatus status, ErrorCode code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = response.getBody();
        Assertions.assertNotNull(body);
        assertThat(body.path("status").asInt()).isEqualTo(status.value());
        assertThat(body.path("code").asText()).isEqualTo(code.name());
        return body;
    }
}
