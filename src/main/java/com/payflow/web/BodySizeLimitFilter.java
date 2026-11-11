package com.payflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.application.ApiException;
import com.payflow.application.ErrorCode;
import com.payflow.application.Problem;
import com.payflow.config.PayflowProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the request body once, refusing anything above the configured limit with a 413 before any
 * handler runs, and hands the exact raw bytes to downstream code. Capturing raw bytes here is also
 * what makes HMAC verification of webhooks possible: the signature is computed over what was sent,
 * never over a re-serialized object.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BodySizeLimitFilter extends OncePerRequestFilter {

  private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

  private final int maxBytes;
  private final ObjectMapper mapper;

  public BodySizeLimitFilter(PayflowProperties properties, ObjectMapper mapper) {
    this.maxBytes = properties.maxBodyBytes();
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!BODY_METHODS.contains(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    if (request.getContentLengthLong() > maxBytes) {
      reject(request, response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
    if (body.length > maxBytes) {
      reject(request, response);
      return;
    }
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    ApiException error =
        new ApiException(
            ErrorCode.PAYLOAD_TOO_LARGE, "The request body exceeds " + maxBytes + " bytes.");
    response.setStatus(error.code().status());
    response.setContentType(Problem.MEDIA_TYPE);
    response.setHeader("Connection", "close");
    mapper.writeValue(response.getOutputStream(), Problem.of(error, request.getRequestURI()));
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream source = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return source.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("synchronous reads only");
        }

        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] target, int off, int len) {
          return source.read(target, off, len);
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
