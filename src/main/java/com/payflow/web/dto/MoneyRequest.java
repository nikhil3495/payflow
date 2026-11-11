package com.payflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Body of capture and refund requests. */
public record MoneyRequest(
    @Schema(description = "Amount in minor units.", example = "4000") @NotNull @Positive
        Long amountMinor,
    @Schema(description = "Must equal the payment's currency.", example = "USD")
        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter upper-case ISO-4217 code")
        String currency,
    @Schema(description = "Free-text reason (refunds).", example = "customer request")
        @Size(max = 255)
        String reason) {}
