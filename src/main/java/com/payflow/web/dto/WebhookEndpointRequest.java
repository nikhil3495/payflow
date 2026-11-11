package com.payflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WebhookEndpointRequest(
    @Schema(
            description = "HTTPS URL that receives signed webhook deliveries.",
            example = "https://merchant.example/hooks/payflow")
        @NotBlank
        @Size(max = 2048)
        String url,
    @Schema(description = "Secret used to sign deliveries (HMAC-SHA256). Never echoed back.")
        @NotBlank
        @Size(min = 24, max = 256)
        String signingSecret) {}
