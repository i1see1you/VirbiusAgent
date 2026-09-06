package io.virbius.auth.api;

import io.virbius.auth.domain.OperatorUser;
import io.virbius.auth.security.LocalJwtVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class OperatorAuthFilter extends OncePerRequestFilter {

    private final LocalJwtVerifier verifier;

    public OperatorAuthFilter(LocalJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/v1/users");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String raw = extractBearer(request);
        Optional<OperatorUser> user = verifier.verify(raw);
        if (user.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        if (!"platform_admin".equals(user.get().role())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"forbidden\"}");
            return;
        }
        OperatorAuthContext.set(request, user.get());
        filterChain.doFilter(request, response);
    }

    static String extractBearer(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return "";
    }
}
