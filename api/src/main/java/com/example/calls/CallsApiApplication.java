package com.example.calls;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Calls API, a stateless Spring Boot service on Cloud Run that looks up phone
 * Calls in the BigQuery table {@code telecom_analytics.calls}.
 *
 * <p>Component scanning starts at this package, so everything under {@code com.example.calls.api}
 * is picked up.
 *
 * @see com.example.calls.api.query.CallsController
 */
@SpringBootApplication
public class CallsApiApplication {

  /**
   * Starts the application.
   *
   * @param args command-line arguments, passed through to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(CallsApiApplication.class, args);
  }
}
