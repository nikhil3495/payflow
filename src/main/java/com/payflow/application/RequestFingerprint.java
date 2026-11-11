package com.payflow.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * SHA-256 over the canonical form of a request body: object keys sorted recursively and whitespace
 * removed, so semantically identical JSON hashes identically regardless of formatting or key order.
 * A body that is not valid JSON falls back to hashing its raw bytes.
 */
public final class RequestFingerprint {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private RequestFingerprint() {}

  public static String of(byte[] body) {
    byte[] canonical;
    try {
      JsonNode tree = MAPPER.readTree(body);
      canonical =
          tree == null || tree.isMissingNode()
              ? new byte[0]
              : MAPPER.writeValueAsBytes(sorted(tree));
    } catch (IOException notJson) {
      canonical = body;
    }
    return sha256Hex(canonical);
  }

  private static JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      TreeMap<String, JsonNode> ordered = new TreeMap<>();
      node.fields().forEachRemaining(e -> ordered.put(e.getKey(), sorted(e.getValue())));
      ObjectNode result = JsonNodeFactory.instance.objectNode();
      ordered.forEach(result::set);
      return result;
    }
    if (node.isArray()) {
      var result = JsonNodeFactory.instance.arrayNode();
      node.forEach(child -> result.add(sorted(child)));
      return result;
    }
    return node;
  }

  static String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }

  public static String sha256Hex(String text) {
    return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }
}
