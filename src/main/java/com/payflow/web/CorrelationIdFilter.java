package com.payflow.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Propagates (or creates) a correlation id, exposes it to logs via MDC and echoes it back. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Logger ACCESS = LoggerFactory.getLogger("payflow.access");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String correlationId =
        incoming != null && SAFE.matcher(incoming).matches()
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, correlationId);
    response.setHeader(HEADER, correlationId);
    long started = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      ACCESS.info(
          "{} {} -> {} in {}ms",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          (System.nanoTime() - started) / 1_000_000);
      MDC.remove(MDC_KEY);
    }
  }
}
