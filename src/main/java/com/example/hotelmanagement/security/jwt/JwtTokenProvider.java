package com.example.hotelmanagement.security.jwt;

import com.example.hotelmanagement.common.exception.ErrorCode;
import com.example.hotelmanagement.common.exception.UnauthorizedException;
import com.example.hotelmanagement.config.AppProperties;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private static final int MIN_SECRET_BYTES = 32;

    private final AppProperties appProperties;

    private byte[] signingKeyBytes;

    @Getter
    private long accessExpirationSeconds;

    @PostConstruct
    public void init() {
        AppProperties.Jwt jwtProps = appProperties.getJwt();
        this.accessExpirationSeconds = jwtProps.getAccessExpirationSeconds();
        String configuredKey = jwtProps.getSigningKey();

        if (!StringUtils.hasText(configuredKey)) {
            if (jwtProps.isAllowEphemeralDevKey()) {
                this.signingKeyBytes = new byte[MIN_SECRET_BYTES];
                new SecureRandom().nextBytes(this.signingKeyBytes);
                log.warn(
                        "JWT_SIGNING_KEY / JWT_SECRET is not set. Generated an ephemeral 256-bit key for this "
                                + "development/test session. Configure JWT_SIGNING_KEY in .env or environment for "
                                + "persistent tokens."
                );
                return;
            }
            throw new IllegalStateException(
                    "JWT signing key is required. Set JWT_SIGNING_KEY (minimum 32 characters) in environment variables."
            );
        }

        byte[] rawBytes = configuredKey.trim().getBytes(StandardCharsets.UTF_8);
        if (rawBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT signing key must be at least 32 bytes (256 bits) for HS256 security."
            );
        }
        this.signingKeyBytes = rawBytes;
    }

    public String generateAccessToken(CustomUserDetails userDetails) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessExpirationSeconds);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(appProperties.getJwt().getIssuer())
                .subject(userDetails.getUsername())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .claim("uid", userDetails.getId())
                .claim("roles", List.copyOf(userDetails.getRoleCodes()))
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claims);
        try {
            signedJWT.sign(new MACSigner(signingKeyBytes));
            return signedJWT.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Failed to sign JWT access token", ex);
        }
    }

    public JWTClaimsSet validateAndParseClaims(String token) {
        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException(ErrorCode.TOKEN_INVALID, "Authentication token is empty");
        }
        try {
            SignedJWT signedJWT = SignedJWT.parse(token.trim());
            if (!JWSAlgorithm.HS256.equals(signedJWT.getHeader().getAlgorithm())) {
                throw new UnauthorizedException(ErrorCode.TOKEN_INVALID, "Unsupported JWT signing algorithm");
            }
            boolean signatureValid = signedJWT.verify(new MACVerifier(signingKeyBytes));
            if (!signatureValid) {
                throw new UnauthorizedException(ErrorCode.TOKEN_INVALID, "Invalid JWT signature");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            String expectedIssuer = appProperties.getJwt().getIssuer();
            if (StringUtils.hasText(expectedIssuer) && !expectedIssuer.equals(claims.getIssuer())) {
                throw new UnauthorizedException(ErrorCode.TOKEN_INVALID, "Invalid JWT issuer");
            }

            Date expirationTime = claims.getExpirationTime();
            if (expirationTime == null || expirationTime.toInstant().isBefore(Instant.now())) {
                throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED, ErrorCode.TOKEN_EXPIRED.getDefaultMessage());
            }

            if (claims.getClaim("uid") == null || !StringUtils.hasText(claims.getSubject())) {
                throw new UnauthorizedException(ErrorCode.TOKEN_INVALID, "JWT is missing required subject claims");
            }
            return claims;
        } catch (ParseException | JOSEException ex) {
            throw new UnauthorizedException(ErrorCode.TOKEN_INVALID, ErrorCode.TOKEN_INVALID.getDefaultMessage());
        }
    }
}
