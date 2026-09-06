package io.virbius.control.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OperatorJwtProperties.class)
public class OperatorJwtConfig {}
