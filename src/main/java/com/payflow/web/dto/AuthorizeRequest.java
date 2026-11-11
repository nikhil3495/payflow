package com.payflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AuthorizeRequest(
    @Schema(description = "Amount in minor units (e.g. cents).", example = "10000")
        @NotNull
        @Positive
        Long amountMinor,
    @Schema(description = "ISO-4217 currency code.", example = "USD")
        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter upper-case ISO-4217 code")
        String currency,
    @Schema(description = "Opaque payment-method token from the client SDK.", example = "tok_visa")
        @NotBlank
        @Size(max = 128)
        String paymentMethodToken,
    @Schema(description = "Merchant's own order reference.", example = "order-1042")
        @Size(max = 255)
        String reference) {}
