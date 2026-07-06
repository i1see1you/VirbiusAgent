package io.virbius.control.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;

public final class BundleMetadataResources {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BundleMetadataResources() {}

    public static Map<String, Object> pocDefault010() {
        try (InputStream in =
                BundleMetadataResources.class.getResourceAsStream("/bundle/poc-default-0.1.0-metadata.json")) {
            if (in == null) {
                return Map.of();
            }
            return MAPPER.readValue(in, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to load poc-default bundle metadata", e);
        }
    }
}
