package com.payflow.config;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfrastructureConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** Thread-safe jitter source (JDK RandomGenerator instances are not). */
  @Bean
  RandomGenerator jitterRandom() {
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        return ThreadLocalRandom.current().nextLong();
      }
    };
  }
}
