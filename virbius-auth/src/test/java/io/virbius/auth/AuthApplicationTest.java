package io.virbius.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthApplicationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    @Test
    void healthAndSchema() {
        ResponseEntity<String> health = http.getForEntity("http://127.0.0.1:" + port + "/api/v1/health", String.class);
        assertEquals(200, health.getStatusCode().value());
        assertTrue(health.getBody() != null && health.getBody().contains("virbius-auth"));

        Integer users = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='tb_operator_user'", Integer.class);
        Integer codes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='tb_auth_code'", Integer.class);
        assertEquals(1, users);
        assertEquals(1, codes);
    }

    @Test
    void loginRejectsOpenRedirectAndIssuesCode() {
        String base = "http://127.0.0.1:" + port;
        ResponseEntity<String> bad = http.getForEntity(
                base + "/login?return_uri=https://evil.example/x&state=s", String.class);
        assertEquals(400, bad.getStatusCode().value());
        assertTrue(bad.getHeaders().getLocation() == null);

        ResponseEntity<String> page = http.getForEntity(
                base + "/login?return_uri=http://127.0.0.1:8080/ui/callback&state=s", String.class);
        assertEquals(200, page.getStatusCode().value());
        assertTrue(page.getBody() != null && page.getBody().contains("for=\"username\""));
        assertTrue(page.getBody().contains("for=\"password\""));
        assertTrue(page.getBody().contains("Virbius 运营登录"));

        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("username", "test-admin");
        form.add("password", "test-pass");
        form.add("return_uri", "http://127.0.0.1:8080/ui/callback");
        form.add("state", "st");
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> ok = http.postForEntity(
                base + "/login", new org.springframework.http.HttpEntity<>(form, headers), String.class);
        assertEquals(302, ok.getStatusCode().value());
        String loc = ok.getHeaders().getLocation().toString();
        assertTrue(loc.startsWith("http://127.0.0.1:8080/ui/callback?"));
        assertTrue(loc.contains("code="));
        assertTrue(loc.contains("state=st"));
    }
}
