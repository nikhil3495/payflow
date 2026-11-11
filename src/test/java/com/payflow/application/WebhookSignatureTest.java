package com.payflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.application.WebhookSignature.Failure;
import com.payflow.application.WebhookSignature.InvalidSignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

  private static final byte[] SECRET =
      "a-very-secret-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
  private static final Duration TOLERANCE = Duration.ofMinutes(5);
  private static final byte[] BODY =
      "{\"id\":\"evt_1\",  \"type\":\"operation.confirmed\"}".getBytes(StandardCharsets.UTF_8);

  private static void assertFails(String header, byte[] body, Instant now, Failure expected) {
    assertThatThrownBy(() -> WebhookSignature.verify(SECRET, header, body, now, TOLERANCE))
        .isInstanceOfSatisfying(
            InvalidSignatureException.class, e -> assertThat(e.failure()).isEqualTo(expected));
  }

  @Test
  void aValidSignatureVerifies() {
    String header = WebhookSignature.header(SECRET, NOW, BODY);
    WebhookSignature.verify(SECRET, header, BODY, NOW, TOLERANCE);
    WebhookSignature.verify(SECRET, header, BODY, NOW.plusSeconds(299), TOLERANCE);
    WebhookSignature.verify(SECRET, header, BODY, NOW.minusSeconds(299), TOLERANCE);
  }

  @Test
  void headerFormatIsTimestampThenHexHmac() {
    assertThat(WebhookSignature.header(SECRET, NOW, BODY))
        .matches("t=" + NOW.getEpochSecond() + ",v1=[0-9a-f]{64}");
  }

  @Test
  void aTamperedBodyIsRejected() {
    String header = WebhookSignature.header(SECRET, NOW, BODY);
    byte[] tampered = BODY.clone();
    tampered[tampered.length - 2] ^= 1;
    assertFails(header, tampered, NOW, Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void aSemanticallyEqualButReserializedBodyIsRejected() {
    // Signing must cover the bytes received: re-serialized JSON has different bytes.
    String header = WebhookSignature.header(SECRET, NOW, BODY);
    byte[] compacted =
        "{\"id\":\"evt_1\",\"type\":\"operation.confirmed\"}".getBytes(StandardCharsets.UTF_8);
    assertFails(header, compacted, NOW, Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void aTamperedSignatureIsRejected() {
    String header = WebhookSignature.header(SECRET, NOW, BODY);
    int last = header.length() - 1;
    char flipped = header.charAt(last) == '0' ? '1' : '0';
    assertFails(header.substring(0, last) + flipped, BODY, NOW, Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void aWrongSecretIsRejected() {
    String header =
        WebhookSignature.header(
            "another-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), NOW, BODY);
    assertFails(header, BODY, NOW, Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void aTamperedTimestampBreaksTheSignature() {
    String header = WebhookSignature.header(SECRET, NOW, BODY);
    String shifted = header.replaceFirst("t=\\d+", "t=" + (NOW.getEpochSecond() + 10));
    assertFails(shifted, BODY, NOW, Failure.SIGNATURE_MISMATCH);
  }

  @Test
  void anExpiredOrFutureTimestampIsRejectedEvenWithAValidSignature() {
    String header = WebhookSignature.header(SECRET, NOW, BODY);
    assertFails(header, BODY, NOW.plusSeconds(301), Failure.TIMESTAMP_OUTSIDE_TOLERANCE);
    assertFails(header, BODY, NOW.minusSeconds(301), Failure.TIMESTAMP_OUTSIDE_TOLERANCE);
    assertFails(header, BODY, NOW.plus(Duration.ofDays(30)), Failure.TIMESTAMP_OUTSIDE_TOLERANCE);
  }

  @Test
  void extremeTimestampsDoNotOverflowTheToleranceCheck() {
    for (long extreme : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1}) {
      byte[] mac = HmacForTest.mac(SECRET, extreme, BODY);
      String header = "t=" + extreme + ",v1=" + java.util.HexFormat.of().formatHex(mac);
      assertFails(header, BODY, NOW, Failure.TIMESTAMP_OUTSIDE_TOLERANCE);
    }
  }

  @Test
  void missingOrMalformedHeadersAreRejected() {
    assertFails(null, BODY, NOW, Failure.MISSING_HEADER);
    assertFails("", BODY, NOW, Failure.MISSING_HEADER);
    assertFails("  ", BODY, NOW, Failure.MISSING_HEADER);
    for (String bad :
        new String[] {
          "garbage",
          "t=abc,v1=00",
          "t=1,v1=zz",
          "t=1",
          "v1=00,t=1",
          "t=1,v1=00,extra=1",
          "t=1,v1=0",
          "t=,v1=",
          "t=1;v1=00"
        }) {
      assertFails(bad, BODY, NOW, Failure.MALFORMED_HEADER);
    }
  }

  /** Independent HMAC implementation for crafting adversarial headers. */
  private static final class HmacForTest {
    static byte[] mac(byte[] secret, long timestamp, byte[] body) {
      try {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(body);
      } catch (java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
