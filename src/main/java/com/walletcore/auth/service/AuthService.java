package com.walletcore.auth.service;

import com.walletcore.auth.dto.AuthResponse;
import com.walletcore.auth.dto.LoginRequest;
import com.walletcore.auth.dto.RefreshRequest;
import com.walletcore.auth.dto.RegisterRequest;
import com.walletcore.auth.entity.RefreshToken;
import com.walletcore.auth.repository.RefreshTokenRepository;
import com.walletcore.auth.security.JwtProperties;
import com.walletcore.auth.security.JwtService;
import com.walletcore.config.error.ApiException;
import com.walletcore.user.entity.User;
import com.walletcore.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService,
                       JwtProperties jwtProperties,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "E-mail already in use");
        }

        var user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        userRepository.save(user);

        log.info("User registered: {}", user.getEmail());
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        refreshTokenRepository.revokeAllByUser(user);
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        var storedToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token not found"));

        if (storedToken.isRevoked() || storedToken.isExpired()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or expired");
        }

        storedToken.revoke();
        var user = storedToken.getUser();

        log.debug("Refreshing tokens for user: {}", user.getEmail());
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(rt -> {
                    rt.revoke();
                    log.info("User logged out: {}", rt.getUser().getEmail());
                });
    }

    private AuthResponse issueTokens(User user) {
        var accessToken = jwtService.generateAccessToken(user);
        var rawRefreshToken = UUID.randomUUID().toString();
        var expiresAt = Instant.now().plusMillis(jwtProperties.getRefreshTokenExpiration());

        refreshTokenRepository.save(new RefreshToken(user, rawRefreshToken, expiresAt));

        return AuthResponse.of(accessToken, rawRefreshToken,
                jwtProperties.getAccessTokenExpiration() / 1000);
    }
}
