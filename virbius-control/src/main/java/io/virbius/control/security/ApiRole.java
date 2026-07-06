package io.virbius.control.security;

public enum ApiRole {
    TENANT_VIEWER(1),
    TENANT_ADMIN(2),
    PLATFORM_ADMIN(3);

    private final int level;

    ApiRole(int level) {
        this.level = level;
    }

    public boolean satisfies(ApiRole required) {
        return required != null && this.level >= required.level;
    }

    public static ApiRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("role required");
        }
        return ApiRole.valueOf(raw.trim().toUpperCase());
    }

    public String value() {
        return name().toLowerCase();
    }
}
