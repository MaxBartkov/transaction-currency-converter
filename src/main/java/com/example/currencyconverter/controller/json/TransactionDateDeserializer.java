package com.example.currencyconverter.controller.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public final class TransactionDateDeserializer extends StdDeserializer<LocalDate> {
    private static final Pattern DATE_FORMAT_PATTERN = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final LocalDate MIN_TRANSACTION_DATE = LocalDate.parse("0001-01-01");
    private static final LocalDate MAX_TRANSACTION_DATE = LocalDate.parse("9999-12-31");
    private static final String INVALID_DATE_MESSAGE =
            "must be a date in yyyy-MM-dd format between %s and %s"
                    .formatted(MIN_TRANSACTION_DATE, MAX_TRANSACTION_DATE);

    public TransactionDateDeserializer() {
        super(LocalDate.class);
    }

    @Override
    public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
        }

        String dateText = parser.getText();
        if (!DATE_FORMAT_PATTERN.matcher(dateText).matches()) {
            return handleInvalidDate(context, dateText);
        }

        LocalDate transactionDate;
        try {
            transactionDate = LocalDate.parse(dateText);
        } catch (DateTimeParseException exception) {
            return handleInvalidDate(context, dateText);
        }

        if (transactionDate.isBefore(MIN_TRANSACTION_DATE) || transactionDate.isAfter(MAX_TRANSACTION_DATE)) {
            return handleInvalidDate(context, dateText);
        }

        return transactionDate;
    }

    private LocalDate handleInvalidDate(DeserializationContext context, String dateText) throws IOException {
        return (LocalDate) context.handleWeirdStringValue(LocalDate.class, dateText, INVALID_DATE_MESSAGE);
    }
}
