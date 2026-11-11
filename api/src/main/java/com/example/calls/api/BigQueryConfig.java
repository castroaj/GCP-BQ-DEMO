package com.example.calls.api;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class BigQueryConfig {
  /** One reused client. Credentials and project come from ADC (the Cloud Run service account). */
  @Bean
  BigQuery bigQuery() {
    return BigQueryOptions.getDefaultInstance().getService();
  }
}
