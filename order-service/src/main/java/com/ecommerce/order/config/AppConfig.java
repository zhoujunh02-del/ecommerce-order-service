package com.ecommerce.order.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    /**
     * RestClient for inventory-service with tight timeouts. Bounding the read
     * timeout is essential: without it, a slow or hung inventory-service would
     * pin an order-service thread indefinitely and drag this service down too.
     */
    @Bean
    RestClient inventoryRestClient(@Value("${inventory.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(500));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Explicit TransactionTemplate so transaction boundaries are visible in the
     * service code. We wrap ONLY database work in it and keep the HTTP reserve call
     * outside — never hold a DB transaction open across a network round-trip.
     */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
