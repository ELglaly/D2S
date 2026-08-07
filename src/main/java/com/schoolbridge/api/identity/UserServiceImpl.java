package com.schoolbridge.api.identity;

import com.schoolbridge.api.common.crypto.BlindIndexHasher;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.common.error.ValidationException;
import com.schoolbridge.api.common.tenancy.TenantContext;
import com.schoolbridge.api.identity.dto.CreateUserRequest;
import com.schoolbridge.api.identity.dto.IdentityMapper;
import com.schoolbridge.api.identity.dto.UserResponse;
import com.schoolbridge.api.tenant.SchoolRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

  private final UserRepository userRepository;
  private final SchoolRepository schoolRepository;
  private final PasswordEncoder passwordEncoder;
  private final BlindIndexHasher blindIndex;
  private final IdentityMapper mapper;

  public UserServiceImpl(
      UserRepository userRepository,
      SchoolRepository schoolRepository,
      PasswordEncoder passwordEncoder,
      BlindIndexHasher blindIndex,
      IdentityMapper mapper) {
    this.userRepository = userRepository;
    this.schoolRepository = schoolRepository;
    this.passwordEncoder = passwordEncoder;
    this.blindIndex = blindIndex;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public UserResponse create(UUID schoolId, CreateUserRequest request) {
    schoolRepository
        .findById(schoolId)
        .orElseThrow(() -> new NotFoundException("error.school.not_found", schoolId));

    // Bound to the target school for the same reason list() and findById() are: this endpoint is
    // SUPER_ADMIN-only and a platform admin carries no school-scoped principal, so TenantContext is
    // empty here. Without the binding the changelog-017 WITH CHECK rejects the INSERT outright —
    // an unbound tenant GUC fails closed, which is correct behaviour and the wrong outcome for a
    // caller who has told us exactly which school they mean.
    return TenantContext.runAs(schoolId, () -> createInSchool(schoolId, request));
  }

  private UserResponse createInSchool(UUID schoolId, CreateUserRequest request) {
    User user;
    if (request.role() == UserRole.PARENT) {
      if (request.phone() == null || request.phone().isBlank()) {
        throw new ValidationException("error.user.phone_required");
      }
      String hash = blindIndex.hash(request.phone());
      if (userRepository.existsByPhoneHash(hash)) {
        throw new ConflictException("error.user.phone_in_use");
      }
      user = User.parent(schoolId, request.name(), request.phone(), hash);
    } else {
      if (request.email() == null || request.email().isBlank()) {
        throw new ValidationException("error.user.email_required");
      }
      if (request.password() == null || request.password().isBlank()) {
        throw new ValidationException("error.user.password_required");
      }
      String normalizedEmail = request.email().trim().toLowerCase(java.util.Locale.ROOT);
      if (userRepository.existsByEmail(normalizedEmail)) {
        throw new ConflictException("error.user.email_in_use");
      }
      user =
          User.staff(
              schoolId,
              request.role(),
              request.name(),
              normalizedEmail,
              passwordEncoder.encode(request.password()));
    }

    return mapper.toResponse(userRepository.save(user));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<UserResponse> list(UUID schoolId, Pageable pageable) {
    schoolRepository
        .findById(schoolId)
        .orElseThrow(() -> new NotFoundException("error.school.not_found", schoolId));

    return TenantContext.runAs(
        schoolId, () -> userRepository.findAll(pageable).map(mapper::toResponse));
  }

  @Override
  @Transactional(readOnly = true)
  public UserResponse findById(UUID schoolId, UUID id) {
    return TenantContext.runAs(
        schoolId,
        () ->
            userRepository
                .findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new NotFoundException("error.user.not_found")));
  }
}
