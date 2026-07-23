package com.soma.backend.domain.user.repository;

import java.util.List;
import java.util.UUID;

/**
 * 대시보드 제안 요약 항목 projection. nickname은 사정사 users.nickname, career·specialties는
 * adjuster_profiles(프로필 없으면 null). speciality(단수)는 서비스가 specialties[] 첫 원소로 파생한다.
 */
public record ProposalItemRow(
    UUID proposalId,
    UUID adjusterId,
    String nickname,
    Integer career,
    List<String> specialties,
    Long estimateMinAmount,
    Long estimateMaxAmount) {
}
