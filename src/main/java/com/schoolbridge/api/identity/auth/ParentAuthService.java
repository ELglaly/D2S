package com.schoolbridge.api.identity.auth;

import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.identity.User;
import com.schoolbridge.api.identity.UserRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.UserStatus;
import com.schoolbridge.api.identity.auth.dto.RequestOtpRequest;
import com.schoolbridge.api.identity.auth.dto.RequestOtpResponse;
import com.schoolbridge.api.identity.auth.dto.VerifyOtpRequest;
import com.schoolbridge.api.identity.auth.dto.VerifyOtpResponse;
import com.schoolbridge.api.identity.otp.OtpDispatcher;
import com.schoolbridge.api.identity.otp.OtpService;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phone-based authentication for parents. The OTP-request endpoint deliberately returns a ticket id
 * even when the phone is unknown so an attacker cannot probe for registered numbers; the verify
 * step will simply fail in that case with {@code error.otp.expired}.
 */
@Slf4j
@Service
public class ParentAuthService {

  private final UserRepository userRepository;
  private final BlindIndexHasher blindIndex;
  private final OtpService otpService;
  private final OtpDispatcher dispatcher;

  public ParentAuthService(
      UserRepository userRepository,
      BlindIndexHasher blindIndex,
      OtpService otpService,
      OtpDispatcher dispatcher) {
    this.userRepository = userRepository;
    this.blindIndex = blindIndex;
    this.otpService = otpService;
    this.dispatcher = dispatcher;
  }

  @Transactional(readOnly = true)
  public RequestOtpResponse requestOtp(RequestOtpRequest request) {
    // Runs without tenant context: parents don't know their schoolId. The repository lookup is
    // unfiltered (TenantFilterAspect skips when TenantContext is empty).
    String phoneHash = blindIndex.hash(request.phone());
    List<User> matches = userRepository.findAllByPhoneHash(phoneHash);
    User parent =
        matches.stream()
            .filter(u -> u.getRole() == UserRole.PARENT && u.getStatus() == UserStatus.ACTIVE)
            .findFirst()
            .orElse(null);

    if (parent == null) {
      // Anti-enumeration: still return a ticket id, but never issue an OTP.
      return new RequestOtpResponse(UUID.randomUUID().toString());
    }

    OtpService.IssuedOtp issued = otpService.issue(parent.getId(), parent.getSchoolId());
    // log.info("code is  {}", issued.code());
    dispatcher.dispatch(request.phone(), issued.code());
    return new RequestOtpResponse(issued.ticketId());
  }

  public VerifyOtpResponse verifyOtp(VerifyOtpRequest request) {
    OtpService.ParentSession session = otpService.verify(request.ticketId(), request.code());
    return VerifyOtpResponse.of(session.token(), session.schoolId());
  }

  /** Revokes the parent session token. Silently succeeds if the token is unknown (idempotent). */
  public void logout(String token) {
    otpService.revoke(token);
  }
}
