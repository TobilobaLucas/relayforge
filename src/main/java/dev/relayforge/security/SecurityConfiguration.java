package dev.relayforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relayforge.common.Problems;
import dev.relayforge.config.RelayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(
            HttpSecurity http, JdbcTemplate jdbc, RelayProperties properties, ObjectMapper mapper)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs",
                                                "/v3/api-docs/**")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/v1/admin/**",
                                                "/actuator/prometheus",
                                                "/actuator/info")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/api/v1/**")
                                        .hasRole("TENANT")
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (req, res, e) -> {
                                                    res.setHeader("WWW-Authenticate", "Bearer");
                                                    Problems.write(
                                                            res,
                                                            mapper,
                                                            HttpStatus.UNAUTHORIZED,
                                                            "unauthorized",
                                                            "A valid bearer token is required.");
                                                })
                                        .accessDeniedHandler(
                                                (req, res, e) ->
                                                        Problems.write(
                                                                res,
                                                                mapper,
                                                                HttpStatus.FORBIDDEN,
                                                                "forbidden",
                                                                "This credential cannot access this route.")))
                .addFilterBefore(
                        new AuthenticationFilter(jdbc, properties, mapper),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
