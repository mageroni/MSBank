package com.msbank.accounts.config;

import com.msbank.accounts.infrastructure.security.InternalTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final String internalToken;

    public SecurityConfig(@Value("${accounts.internal-token}") String internalToken) {
        this.internalToken = internalToken;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        InternalTokenFilter internalFilter = new InternalTokenFilter(internalToken);
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/healthz", "/readyz", "/actuator/**").permitAll()
                .requestMatchers("/internal/**").hasAuthority("ROLE_INTERNAL")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(internalFilter, UsernamePasswordAuthenticationFilter.class)
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthConverter())));
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setPrincipalClaimName("sub");
        return c;
    }
}
