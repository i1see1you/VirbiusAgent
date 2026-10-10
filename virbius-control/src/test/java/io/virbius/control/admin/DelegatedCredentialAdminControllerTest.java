package io.virbius.control.admin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.DelegatedCredentialService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The KEK reveal response must be uncacheable end to end (no intermediate
 * proxy/browser caching of the tenant KEK).
 */
class DelegatedCredentialAdminControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void revealKekIsNoStore() {
        DelegatedCredentialService service = mock(DelegatedCredentialService.class);
        when(service.revealKek("acme")).thenReturn("BASE64KEK");
        DelegatedCredentialAdminController controller =
                new DelegatedCredentialAdminController(service);

        ResponseEntity<ApiResult<Map<String, Object>>> resp = controller.revealKek("acme");

        assertNotNull(resp.getHeaders().getCacheControl());
        assertTrue(resp.getHeaders().getCacheControl().contains("no-store"),
                "Cache-Control must be no-store");
        assertTrue("no-cache".equalsIgnoreCase(resp.getHeaders().getFirst("Pragma")));
        assertNotNull(resp.getBody());
        Map<String, Object> data = (Map<String, Object>) resp.getBody().getData();
        assertTrue("BASE64KEK".equals(data.get("kek_base64")));
    }
}
