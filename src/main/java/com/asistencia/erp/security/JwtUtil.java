package com.asistencia.erp.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration-ms:86400000}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long userId, String username, String role, List<Long> sedeIds) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role)
                .claim("sedesAutorizadas", sedeIds)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return getPayload(token).getSubject();
    }

    public String getRoleFromToken(String token) {
        return getPayload(token).get("role", String.class);
    }

    @SuppressWarnings("unchecked")
    public List<Long> getSedesFromToken(String token) {
        List<?> rawList = getPayload(token).get("sedesAutorizadas", List.class);
        return rawList.stream()
                .map(e -> ((Number) e).longValue())
                .collect(Collectors.toList());
    }

    public Long getUserIdFromToken(String token) {
        return getPayload(token).get("userId", Long.class);
    }

    public boolean validateToken(String token) {
        try {
            getPayload(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims getPayload(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
