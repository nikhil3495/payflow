package com.payflow.support;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that run the whole application against the shared real PostgreSQL. Tests isolate
 * themselves by using a fresh merchant id (see {@link #newMerchant()}), never by truncating tables:
 * the ledger is append-only by design.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", SharedPostgres.INSTANCE::getJdbcUrl);
    registry.add("spring.datasource.username", SharedPostgres.INSTANCE::getUsername);
    registry.add("spring.datasource.password", SharedPostgres.INSTANCE::getPassword);
  }

  @LocalServerPort protected int port;

  @Autowired protected JdbcTemplate jdbc;

  protected Api api() {
    return new Api(port);
  }

  protected static String newMerchant() {
    return "m_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  protected static String newKey() {
    return UUID.randomUUID().toString();
  }
}
