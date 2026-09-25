package com.example.currencyconverter.controller.config;

import com.example.currencyconverter.controller.json.TransactionDateDeserializer;
import com.example.currencyconverter.dto.CreateTransactionRequest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDate;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class JsonRequestConfiguration implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(@NonNull List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jsonConverter) {
                ObjectMapper mapper = strictRequestMapper(jsonConverter.getObjectMapper());
                jsonConverter.registerObjectMappersForType(CreateTransactionRequest.class, mappings -> {
                    mappings.put(MediaType.APPLICATION_JSON, mapper);
                    mappings.put(MediaType.parseMediaType("application/*+json"), mapper);
                });
            }
        }
    }

    private static ObjectMapper strictRequestMapper(ObjectMapper baseMapper) {
        ObjectMapper mapper = baseMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .registerModule(new SimpleModule().addDeserializer(LocalDate.class, new TransactionDateDeserializer()));
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Float)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }
}
