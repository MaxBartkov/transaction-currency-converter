package com.example.currencyconverter.controller.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI transactionOpenApi() throws IOException {
        try (InputStream input = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
            return Yaml.mapper().copy()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(input, OpenAPI.class);
        }
    }
}
