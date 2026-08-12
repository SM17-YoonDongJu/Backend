package com.soma.backend.domain.report.entity;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.crypto.converter.UserClaimAdditionalInformationConverter;
import com.soma.backend.global.security.crypto.converter.UserClaimDescriptionConverter;
import com.soma.backend.global.security.crypto.converter.UserClaimQuestionConverter;

/**
 * USER_CLAIMS Aggregate Root — 사용자가 입력한 사고 상황(리포트 생성 요청의 원본 입력, design.md §3).
 * details(jsonb)는 accidentType별 다형성 값 객체({@link ClaimDetails})이며, 생성 시점에
 * {@code details.type() == accidentType} 불변식을 검증한다(불일치 시 CLAIM_DETAILS_TYPE_MISMATCH).
 */
@Entity
@Table(name = "user_claims")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserClaim extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "product_id")
  private UUID productId;

  @Column(name = "offered_amount")
  private Integer offeredAmount;

  @Column(name = "accident_date")
  private LocalDate accidentDate;

  @Convert(converter = AccidentTypeConverter.class)
  @Column(name = "accident_type", length = 30)
  private AccidentType accidentType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "details", columnDefinition = "jsonb")
  private ClaimDetails details;

  @Convert(converter = UserClaimQuestionConverter.class)
  @Column(name = "question")
  private String question;

  @Convert(converter = UserClaimDescriptionConverter.class)
  @Column(name = "description")
  private String description;

  @Convert(converter = UserClaimAdditionalInformationConverter.class)
  @Column(name = "additional_information")
  private String additionalInformation;

  /** 사고 정보 입력 생성. details와 accidentType이 어긋나면 CLAIM_DETAILS_TYPE_MISMATCH(design.md §3). */
  public static UserClaim create(UUID userId, UUID productId, Integer offeredAmount, LocalDate accidentDate,
      AccidentType accidentType, ClaimDetails details, String question, String description,
      String additionalInformation) {
    if (accidentType == null || details == null || details.type() != accidentType) {
      throw new BusinessException(ErrorCode.CLAIM_DETAILS_TYPE_MISMATCH);
    }
    UserClaim claim = new UserClaim();
    claim.userId = userId;
    claim.productId = productId;
    claim.offeredAmount = offeredAmount;
    claim.accidentDate = accidentDate;
    claim.accidentType = accidentType;
    claim.details = details;
    claim.question = question;
    claim.description = description;
    claim.additionalInformation = additionalInformation;
    return claim;
  }
}
