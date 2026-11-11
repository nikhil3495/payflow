package com.payflow;

import com.payflow.config.PayflowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PayflowProperties.class)
public class PayflowApplication {

  public static void main(String[] args) {
    SpringApplication.run(PayflowApplication.class, args);
  }
}
