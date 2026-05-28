package com.msbank.auth.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msbank.auth.api.dto.Problem;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.net.URI;

/** Stateless security: permitAll on public endpoints; JWT required for /me, /logout, /mfa/**. */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   ObjectMapper mapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/healthz", "/readyz",
                                "/actuator/**",
                                "/.well-known/jwks.json",
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh"
                        ).permitAll()
                        .requestMatchers(
                                "/api/v1/auth/me",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/mfa/**"
                        ).authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> writeProblem(res, mapper, HttpStatus.UNAUTHORIZED, "Unauthorized", e.getMessage(), req.getRequestURI()))
                        .accessDeniedHandler((req, res, e) -> writeProblem(res, mapper, HttpStatus.FORBIDDEN, "Forbidden", e.getMessage(), req.getRequestURI()))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeProblem(HttpServletResponse res, ObjectMapper mapper, HttpStatus status,
                                     String title, String detail, String path) throws java.io.IOException {
        Problem p = new Problem(URI.create("about:blank"), title, status.value(), detail, path, null);
        res.setStatus(status.value());
        res.setContentType(MediaType.parseMediaType("application/problem+json").toString());
        mapper.writeValue(res.getWriter(), p);
    }
}
