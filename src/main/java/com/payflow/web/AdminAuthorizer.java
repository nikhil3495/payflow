package com.payflow.web;

import com.payflow.application.ApiException;
import com.payflow.application.ErrorCode;
import com.payflow.application.RequestFingerprint;
import com.payflow.config.PayflowProperties;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * Guards operator-only endpoints with a shared token. Both sides are hashed first so the
 * constant-time comparison always sees equal-length inputs and the token's length is not leaked.
 */
@Component
public class AdminAuthorizer {

  public static final String HEADER = "X-Admin-Token";

  private final byte[] expectedDigest;

  public AdminAuthorizer(PayflowProperties properties) {
    this.expectedDigest = digest(properties.adminToken());
  }

  public void require(String presented) {
    byte[] actual = digest(presented == null ? "" : presented);
    if (presented == null || !MessageDigest.isEqual(expectedDigest, actual)) {
      throw new ApiException(ErrorCode.UNAUTHORIZED, "A valid " + HEADER + " header is required.");
    }
  }

  private static byte[] digest(String token) {
    return java.util.HexFormat.of().parseHex(RequestFingerprint.sha256Hex(token));
  }
}
