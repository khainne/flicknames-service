package com.flicknames.service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Requires the X-Admin-Key header on every write request (POST/PUT/PATCH/DELETE) under /api,
 * and on every request of any method under /api/v1/admin/ (reads included), apart from CORS
 * preflights. Public read-only GETs outside /api/v1/admin/ stay open for the client app.
 * If ADMIN_API_KEY is not configured, protected requests are refused rather than allowed.
 */
@Slf4j
@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Key";

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** Every request under this prefix needs the key, whatever its method. */
    public static final String ADMIN_PREFIX = "/api/v1/admin/";

    private final byte[] apiKey;

    public AdminApiKeyFilter(@Value("${admin.api-key:}") String apiKey) {
        this.apiKey = apiKey.isBlank() ? null : apiKey.getBytes(StandardCharsets.UTF_8);
        if (this.apiKey == null) {
            log.warn("ADMIN_API_KEY is not set: all admin endpoints and writes will be refused");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizedPath(request);
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (WRITE_METHODS.contains(request.getMethod())) {
            return false;
        }
        // Preflights carry no headers we could check; CorsFilter answers them before this runs.
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        return !isAdminPath(path);
    }

    /**
     * The path the container routes on: decoded, dot-segments and duplicate slashes resolved,
     * ";param" segments removed. The raw request URI keeps those, so "/api/v1/admin;x/db-stats"
     * would slip past a prefix check on it while still reaching the admin controller.
     */
    public static String normalizedPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo != null ? pathInfo : "");
    }

    /** True for requests to /api/v1/admin and anything under it. */
    public static boolean isAdminPath(HttpServletRequest request) {
        return isAdminPath(normalizedPath(request));
    }

    /** True for /api/v1/admin and anything under it. */
    public static boolean isAdminPath(String path) {
        return path.startsWith(ADMIN_PREFIX) || path.equals(ADMIN_PREFIX.substring(0, ADMIN_PREFIX.length() - 1));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (apiKey == null) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "Admin API key is not configured on the server");
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null
                || !MessageDigest.isEqual(apiKey, provided.getBytes(StandardCharsets.UTF_8))) {
            reject(response, HttpStatus.UNAUTHORIZED, "Missing or invalid " + HEADER + " header");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":\"error\",\"message\":\"" + message + "\"}");
    }
}
