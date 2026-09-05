package com.alibou.whatsappclone.security;

import com.alibou.whatsappclone.interceptor.UserSynchronizerFilter;
import com.alibou.whatsappclone.ratelimit.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserSynchronizerFilter userSynchronizerFilter;
    private final RateLimitFilter rateLimitFilter;

    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    /**
     * Public chain (KI-001 fix): docs, swagger, WS handshake and actuator health
     * bypass the OAuth2 resource-server entirely. Previously a single chain with
     * permitAll still ran BearerTokenAuthenticationFilter, so /v3/api-docs
     * without a token -> 401 and with a token ran the user-sync filter -> 500
     * on doc generation. Separate matcher chain fixes both.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/v3/api-docs/**", "/v2/api-docs",
                        "/swagger-resources/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/webjars/**", "/configuration/ui", "/configuration/security",
                        "/ws/**", "/actuator/health", "/actuator/info", "/actuator/metrics")
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(req -> req.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**", "/auth/**")
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(req ->
                        req
                                .requestMatchers("/auth/**").permitAll()
                                .anyRequest().authenticated()
                )
                .oauth2ResourceServer(auth ->
                        auth.jwt(token ->
                                token.jwtAuthenticationConverter(new KeycloakJwtAuthenticationConverter())))
                // Shed abusive load before the DB-touching user-sync filter.
                .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class)
                .addFilterAfter(userSynchronizerFilter, RateLimitFilter.class);
        return http.build();
    }

    @Bean
    public CorsFilter corsFilter() {
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        final CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        config.setAllowedHeaders(Arrays.asList(
                HttpHeaders.ORIGIN,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT,
                HttpHeaders.AUTHORIZATION
        ));
        config.setAllowedMethods(Arrays.asList(
                "GET",
                "POST",
                "DELETE",
                "PUT",
                "PATCH",
                "OPTIONS"
        ));
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);

    }
}
