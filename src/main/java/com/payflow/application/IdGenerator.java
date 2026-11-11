package com.payflow.application;

import com.payflow.domain.Uuid7;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Time-ordered UUID v7 identifiers for everything we create. */
@Component
public class IdGenerator {

  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public IdGenerator(Clock clock) {
    this.clock = clock;
  }

  public UUID next() {
    return Uuid7.generate(clock, random);
  }
}
