package io.virbius.control.config;

import io.virbius.control.gateway.artifact.GatewayArtifactProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GatewayArtifactProperties.class)
public class GatewayArtifactConfig {}
