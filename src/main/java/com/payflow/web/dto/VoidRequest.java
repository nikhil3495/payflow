package com.payflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Optional body of a void request. */
public record VoidRequest(
    @Schema(description = "Free-text reason.", example = "order cancelled") @Size(max = 255)
        String reason) {

  public static final VoidRequest EMPTY = new VoidRequest(null);
}
