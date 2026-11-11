package com.payflow.support;

import com.payflow.PayflowApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A single running application for jqwik properties, which cannot use the Spring JUnit extension.
 * Started lazily on first use against {@link SharedPostgres}; closed by a shutdown hook.
 */
public final class SharedApp {

  private static final ConfigurableApplicationContext CONTEXT;

  static {
    CONTEXT =
        new SpringApplicationBuilder(PayflowApplication.class)
            .web(WebApplicationType.SERVLET)
            .profiles("test")
            // Command-line arguments outrank application.yml; plain default properties would not.
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + SharedPostgres.INSTANCE.getJdbcUrl(),
                "--spring.datasource.username=" + SharedPostgres.INSTANCE.getUsername(),
                "--spring.datasource.password=" + SharedPostgres.INSTANCE.getPassword());
    Runtime.getRuntime().addShutdownHook(new Thread(CONTEXT::close, "shared-app-shutdown"));
  }

  private SharedApp() {}

  public static Api api() {
    return new Api(((WebServerApplicationContext) CONTEXT).getWebServer().getPort());
  }

  public static JdbcTemplate jdbc() {
    return CONTEXT.getBean(JdbcTemplate.class);
  }
}
