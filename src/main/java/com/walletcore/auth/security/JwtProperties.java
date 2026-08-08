package com.walletcore.auth.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {

    /**
     * Chave HMAC de assinatura dos tokens, lida de JWT_SECRET.
     *
     * <p>Validada aqui para que a ausência derrube a aplicação na subida, com mensagem legível, em
     * vez de virar uma WeakKeyException no primeiro login. HS256 exige 256 bits, e o segredo é
     * usado como bytes UTF-8 crus em {@code JwtService.getSigningKey()}, então 32 caracteres é o
     * piso real, não um número arbitrário.
     */
    @NotBlank(message = "security.jwt.secret is required — set the JWT_SECRET environment variable")
    @Size(min = 32, message = "security.jwt.secret must be at least 32 characters (HS256 needs a 256-bit key)")
    private String secret;

    private long accessTokenExpiration;
    private long refreshTokenExpiration;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public long getAccessTokenExpiration() { return accessTokenExpiration; }
    public void setAccessTokenExpiration(long accessTokenExpiration) {
        this.accessTokenExpiration = accessTokenExpiration;
    }

    public long getRefreshTokenExpiration() { return refreshTokenExpiration; }
    public void setRefreshTokenExpiration(long refreshTokenExpiration) {
        this.refreshTokenExpiration = refreshTokenExpiration;
    }
}
