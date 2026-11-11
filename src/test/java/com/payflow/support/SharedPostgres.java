package com.payflow.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real PostgreSQL 16 for the whole test JVM. Started lazily and reaped by Testcontainers' Ryuk
 * sidecar when the JVM exits, so every Spring context and jqwik run shares it.
 */
public final class SharedPostgres {

  public static final PostgreSQLContainer<?> INSTANCE =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("payflow")
          .withUsername("payflow")
          .withPassword("payflow-test-password")
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200");

  static {
    INSTANCE.start();
  }

  private SharedPostgres() {}
}
