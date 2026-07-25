package com.soma.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 매핑되지 않은 경로(404, NoResourceFoundException) 처리의 로깅 정책을 검증한다.
 * actuator 프로브 404는 조용히 두고, 그 외(프론트↔백엔드 계약 불일치 가능) 404만 WARN으로 남긴다.
 */
class GlobalExceptionHandlerNoResourceTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  @Test
  void logsWarnForUnmatchedNonActuatorPath() {
    // 3-arg 생성자의 세 번째 인자가 getResourcePath()로 노출된다(두 번째는 예외 메시지용).
    NoResourceFoundException ex =
        new NoResourceFoundException(HttpMethod.GET, "No static resource", "/api/v1/does-not-exist");

    ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    assertThat(appender.list.get(0).getFormattedMessage()).contains("/api/v1/does-not-exist");
  }

  @Test
  void staysSilentForActuatorProbePath() {
    NoResourceFoundException ex =
        new NoResourceFoundException(HttpMethod.GET, "No static resource", "/actuator/health/readiness");

    ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(appender.list).isEmpty();
  }
}
