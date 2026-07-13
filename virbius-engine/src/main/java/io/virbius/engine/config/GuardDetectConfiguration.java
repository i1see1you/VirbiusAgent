package io.virbius.engine.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GuardDetectProperties.class)
public class GuardDetectConfiguration {}
