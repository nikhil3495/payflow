package com.payflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

  private static String of(String json) {
    return RequestFingerprint.of(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void isSha256HexOfTheCanonicalForm() {
    assertThat(of("{\"a\":1}")).hasSize(64).matches("[0-9a-f]{64}");
    // sha256("{\"a\":1}") computed independently.
    assertThat(of("{ \"a\" : 1 }"))
        .isEqualTo(RequestFingerprint.sha256Hex("{\"a\":1}"))
        .isEqualTo("015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862");
  }

  @Test
  void keyOrderAndWhitespaceDoNotMatterAtAnyDepth() {
    assertThat(of("{\"b\":{\"y\":2,\"x\":1},\"a\":[{\"q\":1,\"p\":2}]}"))
        .isEqualTo(of("{ \"a\": [ {\"p\": 2, \"q\": 1} ],\n \"b\": {\"x\": 1, \"y\": 2} }"));
  }

  @Test
  void differentValuesTypesOrArrayOrderChangeTheFingerprint() {
    assertThat(of("{\"a\":1}")).isNotEqualTo(of("{\"a\":2}"));
    assertThat(of("{\"a\":1}")).isNotEqualTo(of("{\"a\":\"1\"}"));
    assertThat(of("{\"a\":[1,2]}")).isNotEqualTo(of("{\"a\":[2,1]}"));
    assertThat(of("{\"a\":1}")).isNotEqualTo(of("{\"a\":1,\"b\":null}"));
    assertThat(of("{\"a\":null}")).isNotEqualTo(of("{}"));
  }

  @Test
  void emptyAndNonJsonBodiesAreFingerprintedWithoutFailing() {
    assertThat(RequestFingerprint.of(new byte[0])).isEqualTo(RequestFingerprint.sha256Hex(""));
    assertThat(of("not json")).isEqualTo(RequestFingerprint.sha256Hex("not json"));
    assertThat(of("{\"a\":1,\"a\":2}"))
        .isEqualTo(RequestFingerprint.sha256Hex("{\"a\":1,\"a\":2}"));
    assertThat(of("not json")).isNotEqualTo(of("not  json"));
  }

  @Property(tries = 500)
  void shufflingTheKeysOfAnObjectNeverChangesTheFingerprint(
      @ForAll @IntRange(min = 1, max = 12) int size, @ForAll("seed") long seed) {
    List<String> entries = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      entries.add("\"k" + i + "\":" + (i * 31 + seed % 7));
    }
    String reference = "{" + String.join(",", entries) + "}";
    Collections.shuffle(entries, new java.util.Random(seed));
    String shuffled = "{" + entries.stream().collect(Collectors.joining(" , ")) + " }";
    assertThat(of(shuffled)).isEqualTo(of(reference));
  }

  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<Long> seed() {
    return Arbitraries.longs().between(0, 1_000_000);
  }
}
