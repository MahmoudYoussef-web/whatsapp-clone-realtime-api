package com.alibou.whatsappclone.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

/**
 * Extracts authorities from both the "scope" claim (default behaviour) and the
 * Keycloak "resource_access" claim. Defensive against missing/oddly-typed claims:
 * no NPE / ClassCastException when the claim structure is absent, as the original
 * implementation could throw.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt source) {
        return new JwtAuthenticationToken(
                source,
                Stream.concat(
                                new JwtGrantedAuthoritiesConverter().convert(source).stream(),
                                extractResourceRoles(source).stream())
                        .collect(toSet()));
    }

    private Collection<? extends GrantedAuthority> extractResourceRoles(Jwt jwt) {
        Object resourceAccessClaim = jwt.getClaim("resource_access");
        if (!(resourceAccessClaim instanceof Map<?, ?> resourceAccess)) {
            return List.of();
        }

        return resourceAccess.entrySet().stream()
                .map(entry -> extractRolesFromResource(entry.getValue()))
                .flatMap(Collection::stream)
                .distinct()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.replace("-", "_")))
                .collect(toSet());
    }

    private List<String> extractRolesFromResource(Object resourceValue) {
        if (!(resourceValue instanceof Map<?, ?> resource)) {
            return List.of();
        }
        Object rolesClaim = resource.get("roles");
        if (!(rolesClaim instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
