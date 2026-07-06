package io.virbius.engine.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Servlet filter that rejects requests whose {@code X-Virbius-Pool} header does not match
 * <em>this</em> engine instance's resolved pool for the target tenant.
 *
 * <p>If the header is absent the request passes through (backward compat with non-canary
 * deployments). On mismatch a {@code 503} is returned with {@code X-Virbius-Wrong-Pool}.
 *
 * <p>Tenant ID is read from {@code X-Virbius-Tenant-Id} header, or from the request body
 * for {@code /v1/evaluate} POST (the body is cached so downstream filters/controllers can
 * still read it).
 */
@Component
@Order(1)
public class PoolGuardFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(PoolGuardFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NodePoolResolver poolResolver;

    public PoolGuardFilter(NodePoolResolver poolResolver) {
        this.poolResolver = poolResolver;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String headerPool = req.getHeader("X-Virbius-Pool");
        if (headerPool == null || headerPool.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        // Wrap to cache the body so downstream can still read it
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(req);

        String tenantId = req.getHeader("X-Virbius-Tenant-Id");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = extractTenantFromBody(wrapped);
        }
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("PoolGuardFilter: cannot determine tenant, allowing request");
            chain.doFilter(wrapped, response);
            return;
        }

        String instancePool = poolResolver.resolvePool(tenantId);
        if (headerPool.equalsIgnoreCase(instancePool)) {
            chain.doFilter(wrapped, response);
            return;
        }

        log.info("PoolGuardFilter: rejecting request tenant={} headerPool={} instancePool={}",
                tenantId, headerPool, instancePool);
        resp.setStatus(503);
        resp.setHeader("X-Virbius-Wrong-Pool", instancePool);
    }

    private static String extractTenantFromBody(CachedBodyHttpServletRequest req) {
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            return null;
        }
        String path = req.getRequestURI();
        if (!path.equals("/v1/evaluate")) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(req.getInputStream(), Map.class);
            Object t = body.get("tenantId");
            return t instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Servlet request wrapper that caches the body so it can be read multiple times. */
    private static class CachedBodyHttpServletRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        private byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream is = request.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            this.cachedBody = baos.toByteArray();
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public int read() {
                    return bais.read();
                }
                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }
                @Override
                public boolean isReady() {
                    return true;
                }
                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                }
            };
        }
    }
}
