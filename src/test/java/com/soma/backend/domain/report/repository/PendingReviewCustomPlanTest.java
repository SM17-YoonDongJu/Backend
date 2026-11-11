package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.service.PendingReviewQueryService;

/**
 * 검수대기 목록·건수 조회가 자기 트랜잭션 안에서만 custom plan을 강제하고, 커밋 뒤 커넥션 풀에 설정을 남기지
 * 않는지 고정한다(#378). 트랜잭션 경계를 직접 다뤄야 해서 클래스에 @Transactional을 걸지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
class PendingReviewCustomPlanTest {

  private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private PlatformTransactionManager transactionManager;
  @Autowired
  private PendingReviewQueryService pendingReviewQueryService;
  @PersistenceContext
  private EntityManager entityManager;

  private TransactionTemplate readOnlyTx;
  private String region;
  private UUID adjusterId;

  @BeforeEach
  void setUp() {
    readOnlyTx = new TransactionTemplate(transactionManager);
    readOnlyTx.setReadOnly(true);
    region = "PC-" + UUID.randomUUID();
    adjusterId = UUID.randomUUID();
  }

  @Test
  @DisplayName("목록 조회 트랜잭션 안에서는 force_custom_plan이고, 커밋 뒤에는 auto로 돌아온다")
  void listForcesCustomPlanOnlyInsideTransaction() {
    String inside = readOnlyTx.execute(status -> {
      reportRepository.findPendingReviewRows(null, null, region, adjusterId, null, FIRST_PAGE);
      return planCacheMode();
    });

    assertThat(inside).isEqualTo("force_custom_plan");
    assertNoLeakAfterCommit();
  }

  @Test
  @DisplayName("건수 조회가 먼저 불려도 같은 트랜잭션에 force_custom_plan이 걸린다")
  void countAloneForcesCustomPlan() {
    String inside = readOnlyTx.execute(status -> {
      reportRepository.countPendingReviewRows(null, null, region, adjusterId);
      return planCacheMode();
    });

    assertThat(inside).isEqualTo("force_custom_plan");
    assertNoLeakAfterCommit();
  }

  @Test
  @DisplayName("트랜잭션 밖에서 호출해도 예외 없이 조회된다")
  void outsideTransactionDoesNotFail() {
    assertThatCode(() -> {
      reportRepository.findPendingReviewRows(null, null, region, adjusterId, null, FIRST_PAGE);
      reportRepository.countPendingReviewRows(null, null, region, adjusterId);
    }).doesNotThrowAnyException();
    assertNoLeakAfterCommit();
  }

  @Test
  @DisplayName("롤백으로 끝난 트랜잭션도 같은 물리 커넥션에 설정을 남기지 않는다")
  void rollbackDoesNotLeakToPooledConnection() {
    Integer[] backendPid = new Integer[1];
    String inside = readOnlyTx.execute(status -> {
      reportRepository.findPendingReviewRows(null, null, region, adjusterId, null, FIRST_PAGE);
      backendPid[0] = backendPid();
      status.setRollbackOnly();
      return planCacheMode();
    });

    assertThat(inside).isEqualTo("force_custom_plan");
    assertSameConnectionBackToAuto(backendPid[0]);
  }

  @Test
  @DisplayName("커밋 뒤 같은 물리 커넥션(backend pid)을 다시 잡아도 auto다")
  void commitDoesNotLeakToSamePooledConnection() {
    Integer[] backendPid = new Integer[1];
    readOnlyTx.executeWithoutResult(status -> {
      reportRepository.countPendingReviewRows(null, null, region, adjusterId);
      backendPid[0] = backendPid();
    });

    assertSameConnectionBackToAuto(backendPid[0]);
  }

  @Test
  @DisplayName("서비스 경로: 첫 페이지가 덜 차 count가 생략돼도 목록 쿼리 단계에서 설정이 걸린다")
  void servicePathAppliesEvenWhenCountSkipped() {
    // 바깥 readOnly 트랜잭션에 서비스(REQUIRED)를 합류시켜, 서비스가 끝난 뒤에도 같은 트랜잭션에서 값을 본다.
    // 데이터가 없어 ids가 비고 PageableExecutionUtils가 count 로더를 부르지 않는 경로다.
    String inside = readOnlyTx.execute(status -> {
      pendingReviewQueryService.getPendingReviewList(null, null, region, adjusterId, FIRST_PAGE);
      return planCacheMode();
    });

    assertThat(inside).isEqualTo("force_custom_plan");
    assertNoLeakAfterCommit();
  }

  @Test
  @DisplayName("SUPPORTS로 실제 트랜잭션 없이 호출되면 설정을 건너뛴다")
  void supportsWithoutActualTransactionSkips() {
    TransactionTemplate supportsTx = new TransactionTemplate(transactionManager);
    supportsTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);

    String inside = supportsTx.execute(status -> {
      reportRepository.findPendingReviewRows(null, null, region, adjusterId, null, FIRST_PAGE);
      return planCacheMode();
    });

    assertThat(inside).isEqualTo("auto");
    assertNoLeakAfterCommit();
  }

  /** 새 트랜잭션을 몇 번 열어 설정이 남지 않았는지 본다(단일 스레드라 Hikari가 보통 같은 커넥션을 돌려준다). */
  private void assertNoLeakAfterCommit() {
    for (int attempt = 0; attempt < 5; attempt++) {
      String after = readOnlyTx.execute(status -> planCacheMode());
      assertThat(after).isEqualTo("auto");
    }
  }

  /** 설정을 건 그 물리 커넥션이 풀에서 다시 나올 때까지 트랜잭션을 열어, 거기서 auto인지 확인한다. */
  private void assertSameConnectionBackToAuto(int expectedPid) {
    boolean sawSameConnection = false;
    for (int attempt = 0; attempt < 20 && !sawSameConnection; attempt++) {
      Object[] row = readOnlyTx.execute(status -> new Object[] {backendPid(), planCacheMode()});
      if ((Integer) row[0] == expectedPid) {
        sawSameConnection = true;
        assertThat(row[1]).isEqualTo("auto");
      }
    }
    assertThat(sawSameConnection).as("설정을 건 커넥션을 다시 잡아 확인해야 한다").isTrue();
  }

  private int backendPid() {
    return ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
  }

  private String planCacheMode() {
    return (String) entityManager.createNativeQuery("SHOW plan_cache_mode").getSingleResult();
  }
}
