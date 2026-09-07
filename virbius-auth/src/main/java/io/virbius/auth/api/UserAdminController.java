package io.virbius.auth.api;

import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.service.UserAdminService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserAdminController {

    private final UserAdminService users;

    public UserAdminController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping("/api/v1/users")
    public List<Map<String, Object>> list() {
        return users.list().stream().map(UserAdminController::publicView).toList();
    }

    @PostMapping("/api/v1/users")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        try {
            OperatorUser created = users.create(
                    body.get("username"), body.get("password"), body.get("role"), body.get("tenant_id"));
            return ResponseEntity.ok(publicView(created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/v1/users/{id}/disable")
    public ResponseEntity<?> disable(@PathVariable("id") String id) {
        try {
            users.disable(id);
            return ResponseEntity.ok(Map.of("status", "disabled"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> publicView(OperatorUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", user.userId());
        out.put("username", user.username());
        out.put("role", user.role());
        out.put("tenant_id", user.tenantId());
        out.put("status", user.status());
        return out;
    }
}
