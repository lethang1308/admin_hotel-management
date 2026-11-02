package com.example.hotelmanagement.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.hotelmanagement.common.exception.ErrorCode;
import com.example.hotelmanagement.common.exception.UnauthorizedException;
import com.example.hotelmanagement.config.AppProperties;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void shouldGenerateAndValidateAccessToken() throws Exception {
        AppProperties props = new AppProperties();
        props.getJwt().setSigningKey(VALID_SECRET);
        props.getJwt().setIssuer("test-issuer");
        props.getJwt().setAccessExpirationSeconds(300);

        JwtTokenProvider provider = new JwtTokenProvider(props);
        provider.init();

        User user = User.builder()
                .id(42L)
                .fullName("Test User")
                .email("user@test.local")
                .passwordHash("hashed")
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().code("USER").name("User").build()))
                .build();

        String token = provider.generateAccessToken(new CustomUserDetails(user));
        JWTClaimsSet claims = provider.validateAndParseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("user@test.local");
        assertThat(claims.getLongClaim("uid")).isEqualTo(42L);
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER");
    }

    @Test
    void shouldRejectExpiredAccessToken() {
        AppProperties props = new AppProperties();
        props.getJwt().setSigningKey(VALID_SECRET);
        props.getJwt().setAccessExpirationSeconds(-10);

        JwtTokenProvider provider = new JwtTokenProvider(props);
        provider.init();

        User user = User.builder()
                .id(1L)
                .fullName("Expired User")
                .email("expired@test.local")
                .passwordHash("hashed")
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().code("USER").name("User").build()))
                .build();

        String expiredToken = provider.generateAccessToken(new CustomUserDetails(user));

        assertThatThrownBy(() -> provider.validateAndParseClaims(expiredToken))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void shouldRejectTamperedAccessToken() {
        AppProperties props = new AppProperties();
        props.getJwt().setSigningKey(VALID_SECRET);

        JwtTokenProvider provider = new JwtTokenProvider(props);
        provider.init();

        User user = User.builder()
                .id(1L)
                .fullName("Valid User")
                .email("valid@test.local")
                .passwordHash("hashed")
                .status(UserStatus.ACTIVE)
                .roles(Set.of(Role.builder().code("USER").name("User").build()))
                .build();

        String token = provider.generateAccessToken(new CustomUserDetails(user));
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThatThrownBy(() -> provider.validateAndParseClaims(tampered))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

    @Test
    void shouldFailFastInProductionWhenSecretIsMissingOrTooShort() {
        AppProperties missingProps = new AppProperties();
        missingProps.getJwt().setAllowEphemeralDevKey(false);
        missingProps.getJwt().setSigningKey("");

        JwtTokenProvider provider1 = new JwtTokenProvider(missingProps);
        assertThatThrownBy(provider1::init).isInstanceOf(IllegalStateException.class);

        AppProperties shortProps = new AppProperties();
        shortProps.getJwt().setAllowEphemeralDevKey(false);
        shortProps.getJwt().setSigningKey("too-short-secret");

        JwtTokenProvider provider2 = new JwtTokenProvider(shortProps);
        assertThatThrownBy(provider2::init).isInstanceOf(IllegalStateException.class);
    }
}
