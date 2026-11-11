package com.payflow.web;

import com.payflow.application.ApiException;
import com.payflow.application.ErrorCode;
import com.payflow.application.ReconciliationService;
import com.payflow.infrastructure.ReconciliationRepository.Discrepancy;
import com.payflow.infrastructure.ReconciliationRepository.Run;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator view of the nightly reconciliation job. Requires the admin token. */
@RestController
@RequestMapping("/v1/reconciliation")
@Tag(name = "Reconciliation")
public class ReconciliationController {

  public record RunResponse(
      UUID id,
      String state,
      Instant startedAt,
      Instant finishedAt,
      long paymentsChecked,
      long discrepancyCount,
      String failureReason) {
    static RunResponse from(Run run) {
      return new RunResponse(
          run.id(),
          run.state(),
          run.startedAt(),
          run.finishedAt(),
          run.paymentsChecked(),
          run.discrepancyCount(),
          run.failureReason());
    }
  }

  public record DiscrepancyResponse(
      UUID id,
      UUID paymentId,
      String kind,
      String subject,
      long expectedMinor,
      long actualMinor,
      Instant detectedAt) {
    static DiscrepancyResponse from(Discrepancy d) {
      return new DiscrepancyResponse(
          d.id(),
          d.paymentId(),
          d.kind(),
          d.subject(),
          d.expectedMinor(),
          d.actualMinor(),
          d.detectedAt());
    }
  }

  public record RunDetailResponse(RunResponse run, List<DiscrepancyResponse> discrepancies) {
    public RunDetailResponse {
      discrepancies = List.copyOf(discrepancies);
    }
  }

  private final ReconciliationService service;
  private final AdminAuthorizer admin;

  public ReconciliationController(ReconciliationService service, AdminAuthorizer admin) {
    this.service = service;
    this.admin = admin;
  }

  @GetMapping("/runs")
  @Operation(summary = "List the most recent reconciliation runs")
  public List<RunResponse> runs(
      @Parameter(required = true) @RequestHeader(value = AdminAuthorizer.HEADER, required = false)
          String token,
      @RequestParam(defaultValue = "20") int limit) {
    admin.require(token);
    return service.latestRuns(Math.max(1, Math.min(limit, 100))).stream()
        .map(RunResponse::from)
        .toList();
  }

  @GetMapping("/runs/{id}")
  @Operation(summary = "A reconciliation run with its discrepancies")
  public RunDetailResponse run(
      @Parameter(required = true) @RequestHeader(value = AdminAuthorizer.HEADER, required = false)
          String token,
      @PathVariable String id) {
    admin.require(token);
    var detail = service.detail(parse(id));
    return new RunDetailResponse(
        RunResponse.from(detail.run()),
        detail.discrepancies().stream().map(DiscrepancyResponse::from).toList());
  }

  @GetMapping("/runs/{id}/discrepancies")
  @Operation(summary = "Discrepancies recorded by one reconciliation run")
  public List<DiscrepancyResponse> discrepancies(
      @Parameter(required = true) @RequestHeader(value = AdminAuthorizer.HEADER, required = false)
          String token,
      @PathVariable String id) {
    admin.require(token);
    return service.detail(parse(id)).discrepancies().stream()
        .map(DiscrepancyResponse::from)
        .toList();
  }

  private static UUID parse(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.NOT_FOUND, "No such reconciliation run.");
    }
  }
}
