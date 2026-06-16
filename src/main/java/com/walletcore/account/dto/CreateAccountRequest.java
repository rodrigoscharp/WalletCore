package com.walletcore.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(min = 2, max = 100) String name,
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO code") String currency
) {
    public CreateAccountRequest {
        if (currency == null || currency.isBlank()) currency = "BRL";
    }
}
