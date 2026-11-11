package com.payflow.domain;

import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** RFC 9562 UUID version 7: 48-bit unix-ms timestamp, then random bits. Time-ordered. */
public final class Uuid7 {

  private Uuid7() {}

  public static UUID generate(Clock clock, RandomGenerator random) {
    long millis = clock.millis();
    long msb = (millis << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
    long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb);
  }
}
