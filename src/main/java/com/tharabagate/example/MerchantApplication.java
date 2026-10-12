package com.tharabagate.example;

import com.tharabagate.*;
import com.tharabagate.payment.PaymentClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MerchantApplication {
  public static void main(String[] args) { SpringApplication.run(MerchantApplication.class, args); }
  @Bean
  TharabaGateClient tharabaGateClient(@Value("${merchant.api-key}") String key,
      @Value("${merchant.environment}") Environment environment,
      @Value("${merchant.base-url}") String baseUrl) {
    if (environment != Environment.TEST) throw new IllegalArgumentException("This example requires TEST");
    return TharabaGateClient.builder().apiKey(key).environment(Environment.TEST).baseUrl(baseUrl).build();
  }
  @Bean PaymentClient paymentClient(TharabaGateClient client) { return client.payments(); }
}
