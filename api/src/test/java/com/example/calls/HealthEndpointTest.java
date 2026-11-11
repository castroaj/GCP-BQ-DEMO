package com.example.calls;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.google.cloud.bigquery.BigQuery;

/**
 * Checks that {@code GET /health} is served by Spring Boot Actuator rather than a controller.
 *
 * <p>The BigQuery client is mocked so the context starts without credentials.
 */
@SpringBootTest(properties = "calls.dataset=telecom_analytics")
@AutoConfigureMockMvc
class HealthEndpointTest {
  @Autowired MockMvc mvc;
  // Keeps the real BigQuery client (and ADC) out of the test context.
  @MockitoBean BigQuery bigQuery;

  // /health comes from Spring Boot Actuator (base path "/"), not a controller.
  /**
   * Verifies that {@code /health} answers {@code 200} with status {@code UP}.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void healthReturnsUp() throws Exception {
    mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
  }
}
