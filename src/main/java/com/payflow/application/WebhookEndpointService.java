package com.payflow.application;

import com.payflow.infrastructure.WebhookRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Registers where a merchant wants outbound webhooks delivered. */
@Service
public class WebhookEndpointService {

  private final WebhookRepository webhooks;
  private final WebhookTargetValidator validator;
  private final Clock clock;

  public WebhookEndpointService(
      WebhookRepository webhooks, WebhookTargetValidator validator, Clock clock) {
    this.webhooks = webhooks;
    this.validator = validator;
    this.clock = clock;
  }

  /** Returns true if the endpoint was created, false if an existing one was replaced. */
  public boolean register(String merchantId, String url, String signingSecret) {
    try {
      validator.validate(url);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          "The webhook url is not acceptable.",
          List.of(new ApiException.FieldIssue("url", e.getMessage())),
          null);
    }
    return webhooks.upsertEndpoint(merchantId, url, signingSecret, clock.instant());
  }
}
