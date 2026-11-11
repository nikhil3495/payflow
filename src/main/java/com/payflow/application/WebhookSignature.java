package com.payflow.application;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 webhook signatures in the form {@code t=<unix seconds>,v1=<hex>}.
 *
 * <p>The signed message is {@code "<t>." + <raw body bytes>}. The timestamp is inside the signed
 * payload, so an attacker cannot replay an old body under a fresh timestamp, and the verifier
 * rejects timestamps outside a tolerance window. The body is always the exact bytes received -
 * never a re-serialized object - and signatures are compared in constant time.
 */
public final class WebhookSignature {

  /** Why verification failed. Logged server-side only; clients just see a 401. */
  public enum Failure {
    MISSING_HEADER,
    MALFORMED_HEADER,
    TIMESTAMP_OUTSIDE_TOLERANCE,
    SIGNATURE_MISMATCH
  }

  public static final class InvalidSignatureException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Failure failure;

    InvalidSignatureException(Failure failure) {
      super(failure.name());
      this.failure = failure;
    }

    public Failure failure() {
      return failure;
    }
  }

  private static final String ALGORITHM = "HmacSHA256";

  private WebhookSignature() {}

  public static String header(byte[] secret, Instant timestamp, byte[] body) {
    long seconds = timestamp.getEpochSecond();
    return "t=" + seconds + ",v1=" + HexFormat.of().formatHex(hmac(secret, seconds, body));
  }

  public static void verify(
      byte[] secret, String header, byte[] body, Instant now, Duration tolerance) {
    if (header == null || header.isBlank()) {
      throw new InvalidSignatureException(Failure.MISSING_HEADER);
    }
    long timestamp;
    byte[] provided;
    try {
      String[] parts = header.split(",", -1);
      if (parts.length != 2 || !parts[0].startsWith("t=") || !parts[1].startsWith("v1=")) {
        throw new InvalidSignatureException(Failure.MALFORMED_HEADER);
      }
      timestamp = Long.parseLong(parts[0].substring(2));
      provided = HexFormat.of().parseHex(parts[1].substring(3));
    } catch (IllegalArgumentException e) {
      throw new InvalidSignatureException(Failure.MALFORMED_HEADER);
    }

    // Check the signature first so the timing of the tolerance check reveals nothing about it.
    boolean signatureMatches = MessageDigest.isEqual(hmac(secret, timestamp, body), provided);
    if (!withinTolerance(now.getEpochSecond(), timestamp, tolerance)) {
      throw new InvalidSignatureException(Failure.TIMESTAMP_OUTSIDE_TOLERANCE);
    }
    if (!signatureMatches) {
      throw new InvalidSignatureException(Failure.SIGNATURE_MISMATCH);
    }
  }

  /** Overflow-safe: an absurd attacker-supplied timestamp is simply "outside the window". */
  private static boolean withinTolerance(long nowSeconds, long timestamp, Duration tolerance) {
    try {
      return Math.absExact(Math.subtractExact(nowSeconds, timestamp)) <= tolerance.toSeconds();
    } catch (ArithmeticException overflow) {
      return false;
    }
  }

  private static byte[] hmac(byte[] secret, long timestampSeconds, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      mac.update(Long.toString(timestampSeconds).getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) '.');
      return mac.doFinal(body);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
    }
  }
}
