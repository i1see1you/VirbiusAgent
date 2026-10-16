package io.virbius.control.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.virbius.control.common.response.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link GlobalExceptionHandler#handleBusiness} must surface the domain code as the
 * HTTP status (401/403/404/409/423/429/502/503...), not collapse everything but
 * 404/409 into 400. The JSON body always carries the original code either way.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsDomainCodeToHttpStatus() {
        assertStatus(400, HttpStatus.BAD_REQUEST);
        assertStatus(401, HttpStatus.UNAUTHORIZED);
        assertStatus(403, HttpStatus.FORBIDDEN);
        assertStatus(404, HttpStatus.NOT_FOUND);
        assertStatus(409, HttpStatus.CONFLICT);
        assertStatus(423, HttpStatus.LOCKED);
        assertStatus(429, HttpStatus.TOO_MANY_REQUESTS);
        assertStatus(502, HttpStatus.BAD_GATEWAY);
        assertStatus(503, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void unknownOrNonErrorCodeFallsBackToBadRequest() {
        assertStatus(999, HttpStatus.BAD_REQUEST);
        assertStatus(200, HttpStatus.BAD_REQUEST);
    }

    @Test
    void bodyStillCarriesTheOriginalCode() {
        ResponseEntity<ApiResult<Void>> resp =
                handler.handleBusiness(new BusinessException(429, "exchange rate limit exceeded"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        assertEquals(429, resp.getBody().getCode());
    }

    private void assertStatus(int code, HttpStatus expected) {
        ResponseEntity<ApiResult<Void>> resp = handler.handleBusiness(new BusinessException(code, "x"));
        assertEquals(expected, resp.getStatusCode(), "domain code " + code);
    }
}
