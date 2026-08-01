package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.entity.claim.MedicalIndemnityDetails;
import com.soma.backend.domain.report.entity.claim.TrafficDetails;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** UserClaim.create 불변식(details.type == accidentType) 검증(design.md §3). */
class UserClaimTest {

  @Test
  void create_success_whenDetailsTypeMatchesAccidentType() {
    ClaimDetails details = ClaimDetails.of(AccidentType.MEDICAL_INDEMNITY, List.of("급성 충수염"), List.of());

    UserClaim claim = UserClaim.create(
        UUID.randomUUID(), null, 1000L, LocalDate.now(),
        AccidentType.MEDICAL_INDEMNITY, details, "질문", "사고 경위", null);

    assertThat(claim.getDetails()).isInstanceOf(MedicalIndemnityDetails.class);
    assertThat(claim.getAccidentType()).isEqualTo(AccidentType.MEDICAL_INDEMNITY);
  }

  @Test
  void create_throws_whenDetailsTypeMismatchesAccidentType() {
    ClaimDetails trafficDetails = new TrafficDetails(List.of("급성 충수염"), List.of());

    assertThatThrownBy(() -> UserClaim.create(
        UUID.randomUUID(), null, 1000L, LocalDate.now(),
        AccidentType.MEDICAL_INDEMNITY, trafficDetails, "질문", "사고 경위", null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CLAIM_DETAILS_TYPE_MISMATCH));
  }
}
