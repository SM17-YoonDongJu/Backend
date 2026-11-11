package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** ReportReviewIssue 팩토리의 검수 상태별 필수값 단위 테스트. */
class ReportReviewIssueTest {

  private static final UUID ISSUE_ID = UUID.randomUUID();

  @Test
  @DisplayName("ACCEPTED는 사유 없이 만들 수 있다")
  void acceptedNeedsNoReason() {
    ReportReviewIssue issue = ReportReviewIssue.create(
        ISSUE_ID, "제목", "설명", 100, IssueReviewStatus.ACCEPTED, "의견", null, null);

    assertThat(issue.getReviewStatus()).isEqualTo(IssueReviewStatus.ACCEPTED);
    assertThat(issue.isExcluded()).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  @DisplayName("ADDED는 제목이 비면 MISSING_REQUIRED_FIELD")
  void addedRequiresTitle(String title) {
    assertMissingRequired(() -> ReportReviewIssue.create(
        null, title, "설명", 100, IssueReviewStatus.ADDED, null, null, null));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @DisplayName("ADDED는 설명이 비면 MISSING_REQUIRED_FIELD")
  void addedRequiresDescription(String description) {
    assertMissingRequired(() -> ReportReviewIssue.create(
        null, "제목", description, 100, IssueReviewStatus.ADDED, null, null, null));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @DisplayName("MODIFIED는 수정 사유가 비면 MISSING_REQUIRED_FIELD")
  void modifiedRequiresReason(String modifiedReason) {
    assertMissingRequired(() -> ReportReviewIssue.create(
        ISSUE_ID, "제목", "설명", 100, IssueReviewStatus.MODIFIED, null, modifiedReason, null));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @DisplayName("EXCLUDED는 제외 사유가 비면 MISSING_REQUIRED_FIELD")
  void excludedRequiresReason(String excludedReason) {
    assertMissingRequired(() -> ReportReviewIssue.create(
        ISSUE_ID, "제목", "설명", 100, IssueReviewStatus.EXCLUDED, null, null, excludedReason));
  }

  @Test
  @DisplayName("사유가 있는 EXCLUDED 쟁점은 isExcluded가 참이다")
  void excludedWithReason() {
    ReportReviewIssue issue = ReportReviewIssue.create(
        ISSUE_ID, "제목", "설명", 100, IssueReviewStatus.EXCLUDED, null, null, "중복 청구");

    assertThat(issue.isExcluded()).isTrue();
  }

  private static void assertMissingRequired(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
  }
}
