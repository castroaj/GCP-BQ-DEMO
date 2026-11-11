package com.example.calls.api;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration that provides the shared BigQuery client. */
@Configuration
class BigQueryConfig {

  /**
   * Creates the single, reused BigQuery client.
   *
   * <p>Credentials and the default project come from Application Default Credentials: the {@code
   * calls-api} service account on Cloud Run, or the developer's login locally. No key file is
   * involved.
   *
   * @return the client used for every query
   */
  @Bean
  BigQuery bigQuery() {
    return BigQueryOptions.getDefaultInstance().getService();
  }
}
