package com.ecommerce.inventory.infra.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    @Bean
    RedisScript<Long> deductScript() {
        return RedisScript.of(new ClassPathResource("lua/deduct.lua"), Long.class);
    }
}
