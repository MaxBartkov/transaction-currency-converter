package com.example.currencyconverter.client.exception;

import lombok.Getter;

@Getter
public class TreasuryException extends RuntimeException {
    private final boolean unavailable;

    private TreasuryException(boolean unavailable, String reason, Throwable cause) {
        super(reason, cause);
        this.unavailable = unavailable;
    }

    public static TreasuryException unavailable(Throwable cause) {
        return new TreasuryException(true, "Treasury request could not be completed", cause);
    }

    public static TreasuryException invalidResponse(String reason) {
        return invalidResponse(reason, null);
    }

    public static TreasuryException invalidResponse(String reason, Throwable cause) {
        return new TreasuryException(false, reason, cause);
    }

    public String getPublicMessage() {
        return unavailable ? "Treasury exchange rate service is temporarily unavailable"
                : "Treasury exchange rate service returned an invalid response";
    }
}
