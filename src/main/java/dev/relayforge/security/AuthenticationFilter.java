package dev.relayforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relayforge.common.Problems;
import dev.relayforge.config.RelayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthenticationFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final RelayProperties properties;
    private final ObjectMapper mapper;

    public AuthenticationFilter(
            JdbcTemplate jdbc, RelayProperties properties, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() <= 200) {
            String token = header.substring(7);
            if (Credentials.matches(token, properties.adminToken())) {
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        "operator",
                                        null,
                                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            } else {
                List<UUID> ids;
                try {
                    ids =
                            jdbc.query(
                                    "SELECT id FROM projects WHERE api_key_hash = ?",
                                    (rs, n) -> rs.getObject(1, UUID.class),
                                    Credentials.hash(token));
                } catch (org.springframework.dao.DataAccessException e) {
                    Problems.write(
                            response,
                            mapper,
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "authentication_unavailable",
                            "Authentication is temporarily unavailable.");
                    return;
                }
                if (!ids.isEmpty()) {
                    SecurityContextHolder.getContext()
                            .setAuthentication(
                                    new UsernamePasswordAuthenticationToken(
                                            new Tenant(ids.getFirst()),
                                            null,
                                            List.of(new SimpleGrantedAuthority("ROLE_TENANT"))));
                }
            }
        }
        chain.doFilter(request, response);
    }
}
