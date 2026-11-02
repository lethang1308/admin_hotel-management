package com.example.hotelmanagement.modules.auth.service;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.DuplicateResourceException;
import com.example.hotelmanagement.common.exception.ErrorCode;
import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.common.exception.UnauthorizedException;
import com.example.hotelmanagement.common.util.TokenHashUtils;
import com.example.hotelmanagement.config.AppProperties;
import com.example.hotelmanagement.modules.auth.dto.AuthTokenResponse;
import com.example.hotelmanagement.modules.auth.dto.ChangePasswordRequest;
import com.example.hotelmanagement.modules.auth.dto.LoginRequest;
import com.example.hotelmanagement.modules.auth.dto.LogoutRequest;
import com.example.hotelmanagement.modules.auth.dto.RefreshTokenRequest;
import com.example.hotelmanagement.modules.auth.dto.RegisterRequest;
import com.example.hotelmanagement.modules.auth.entity.RefreshToken;
import com.example.hotelmanagement.modules.auth.repository.RefreshTokenRepository;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.service.RoleService;
import com.example.hotelmanagement.modules.user.dto.UserResponse;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.mapper.UserMapper;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;
import com.example.hotelmanagement.security.authorization.SecurityConstants;
import com.example.hotelmanagement.security.jwt.JwtTokenProvider;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleService roleService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserMapper userMapper;
    private final AppProperties appProperties;

    @Transactional
    public AuthTokenResponse register(RegisterRequest request, String ipAddress, String userAgent) {
        String normalizedEmail = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateResourceException("An account with email '" + normalizedEmail + "' already exists");
        }

        Role userRole = roleService.getRequiredByCode(SecurityConstants.ROLE_USER);
        Set<Role> roles = new LinkedHashSet<>();
        roles.add(userRole);

        User newUser = User.builder()
                .fullName(request.getFullName().trim())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .status(UserStatus.ACTIVE)
                .roles(roles)
                .build();

        User saved = userRepository.save(newUser);
        log.info("Registered new user account id={} email={}", saved.getId(), saved.getEmail());
        return issueTokens(saved, UUID.randomUUID().toString(), ipAddress, userAgent);
    }

    @Transactional
    public AuthTokenResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String normalizedEmail = normalizeEmail(request.getEmail());
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, request.getPassword())
        );

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException(
                        ErrorCode.INVALID_CREDENTIALS,
                        ErrorCode.INVALID_CREDENTIALS.getDefaultMessage()
                ));

        if (!user.isActive()) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, ErrorCode.ACCOUNT_DISABLED.getDefaultMessage());
        }

        log.info("User logged in successfully id={} email={}", user.getId(), user.getEmail());
        return issueTokens(user, UUID.randomUUID().toString(), ipAddress, userAgent);
    }

    @Transactional(noRollbackFor = AppException.class)
    public AuthTokenResponse refresh(RefreshTokenRequest request, String ipAddress, String userAgent) {
        String incomingHash = TokenHashUtils.sha256Hex(request.getRefreshToken().trim());
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(incomingHash)
                .orElseThrow(() -> new UnauthorizedException(
                        ErrorCode.TOKEN_INVALID,
                        "Refresh token is invalid"
                ));

        Instant now = Instant.now();

        if (storedToken.isRevoked()) {
            if (storedToken.getReplacedByTokenHash() != null) {
                int revokedCount = refreshTokenRepository.revokeAllActiveByFamilyId(storedToken.getFamilyId(), now);
                log.warn(
                        "Detected replay of rotated refresh token for userId={} familyId={}. Revoked {} active token(s).",
                        storedToken.getUser().getId(),
                        storedToken.getFamilyId(),
                        revokedCount
                );
            }
            throw new UnauthorizedException(ErrorCode.TOKEN_REVOKED, ErrorCode.TOKEN_REVOKED.getDefaultMessage());
        }

        if (storedToken.isExpired()) {
            storedToken.setRevokedAt(now);
            refreshTokenRepository.save(storedToken);
            throw new UnauthorizedException(ErrorCode.TOKEN_EXPIRED, "Refresh token has expired");
        }

        User user = storedToken.getUser();
        if (!user.isActive()) {
            refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, ErrorCode.ACCOUNT_DISABLED.getDefaultMessage());
        }

        String newRawRefreshToken = TokenHashUtils.generateOpaqueToken();
        String newTokenHash = TokenHashUtils.sha256Hex(newRawRefreshToken);

        storedToken.setRevokedAt(now);
        storedToken.setReplacedByTokenHash(newTokenHash);
        refreshTokenRepository.save(storedToken);

        RefreshToken rotatedEntity = RefreshToken.builder()
                .user(user)
                .tokenHash(newTokenHash)
                .familyId(storedToken.getFamilyId())
                .expiresAt(now.plusSeconds(appProperties.getJwt().getRefreshExpirationSeconds()))
                .ipAddress(truncate(ipAddress, 64))
                .userAgent(truncate(userAgent, 255))
                .createdAt(now)
                .build();
        refreshTokenRepository.save(rotatedEntity);

        CustomUserDetails userDetails = new CustomUserDetails(user);
        String accessToken = jwtTokenProvider.generateAccessToken(userDetails);

        return AuthTokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(newRawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessExpirationSeconds())
                .user(userMapper.toResponse(user))
                .build();
    }

    @Transactional
    public void logout(LogoutRequest request) {
        String tokenHash = TokenHashUtils.sha256Hex(request.getRefreshToken().trim());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.setRevokedAt(Instant.now());
                refreshTokenRepository.save(token);
                log.info("Revoked refresh token session for userId={}", token.getUser().getId());
            }
        });
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.findWithRolesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return userMapper.toResponse(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findWithRolesById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Current password is incorrect");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_RULE_VIOLATION,
                    "New password must be different from current password"
            );
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId, Instant.now());
        log.info("Changed password for userId={} and revoked {} active refresh token(s)", userId, revoked);
    }

    private AuthTokenResponse issueTokens(User user, String familyId, String ipAddress, String userAgent) {
        Instant now = Instant.now();
        String rawRefreshToken = TokenHashUtils.generateOpaqueToken();
        String refreshTokenHash = TokenHashUtils.sha256Hex(rawRefreshToken);

        RefreshToken refreshEntity = RefreshToken.builder()
                .user(user)
                .tokenHash(refreshTokenHash)
                .familyId(familyId)
                .expiresAt(now.plusSeconds(appProperties.getJwt().getRefreshExpirationSeconds()))
                .ipAddress(truncate(ipAddress, 64))
                .userAgent(truncate(userAgent, 255))
                .createdAt(now)
                .build();
        refreshTokenRepository.save(refreshEntity);

        CustomUserDetails userDetails = new CustomUserDetails(user);
        String accessToken = jwtTokenProvider.generateAccessToken(userDetails);

        return AuthTokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessExpirationSeconds())
                .user(userMapper.toResponse(user))
                .build();
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
