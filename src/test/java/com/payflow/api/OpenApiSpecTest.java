package com.payflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payflow.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The committed {@code docs/openapi.json} must match what the running application generates.
 * Regenerate with {@code mvn test -Dtest=OpenApiSpecTest -Dopenapi.update=true}.
 */
class OpenApiSpecTest extends IntegrationTest {

  private static final Path SPEC = Path.of("docs", "openapi.json");
  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  @Test
  void committedSpecMatchesTheGeneratedOne() throws IOException {
    JsonNode generated = canonical(api().get("/v3/api-docs", null).json());
    String rendered = MAPPER.writeValueAsString(generated) + System.lineSeparator();

    if (Boolean.getBoolean("openapi.update")) {
      Files.writeString(SPEC, rendered);
    }

    assertThat(SPEC).as("docs/openapi.json must be committed").exists();
    assertThat(canonical(MAPPER.readTree(Files.readString(SPEC))))
        .as("docs/openapi.json is stale; regenerate with -Dopenapi.update=true")
        .isEqualTo(generated);
  }

  /** Sorts keys recursively and drops the per-run server URL (it contains a random port). */
  private static JsonNode canonical(JsonNode node) {
    JsonNode sorted = sort(node);
    if (sorted instanceof ObjectNode object) {
      object.remove("servers");
    }
    return sorted;
  }

  private static JsonNode sort(JsonNode node) {
    if (node.isObject()) {
      TreeMap<String, JsonNode> ordered = new TreeMap<>();
      node.fields().forEachRemaining(e -> ordered.put(e.getKey(), sort(e.getValue())));
      ObjectNode result = MAPPER.createObjectNode();
      ordered.forEach(result::set);
      return result;
    }
    if (node.isArray()) {
      var result = MAPPER.createArrayNode();
      node.forEach(child -> result.add(sort(child)));
      return result;
    }
    return node;
  }
}
