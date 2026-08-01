package com.soma.backend.domain.upload.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.upload.service.UploadService;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;
import com.soma.backend.infra.s3.S3UploadService;

/**
 * UploadController 슬라이스 테스트. 실제 UploadService·UploadPurpose 파싱·GlobalExceptionHandler를
 * 시큐리티 체인과 함께 배선하고 S3 경계(S3UploadService)만 @MockitoBean으로 대체한다. multipart(file·purpose)
 * 수신, purpose×content_type 교차 검증, 매직바이트 위장 탐지, 용량 초과, purpose 파싱(소문자 계약, 대문자·미지값
 * 거부), 필수 파트 누락, 인증 경계, snake_case 응답 봉투를 검증한다.
 */
@WebMvcTest(UploadController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    UploadService.class
})
@DisplayName("UploadController 슬라이스 테스트")
class UploadControllerTest {

  private static final String S3_URL =
      "https://test-bucket.s3.ap-northeast-2.amazonaws.com/avatars/uuid.png";
  private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
  private static final byte[] PDF_BYTES = {0x25, 0x50, 0x44, 0x46, 0x2D};

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private S3UploadService s3UploadService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private MockMultipartFile filePart(String name, String contentType, byte[] bytes) {
    return new MockMultipartFile("file", name, contentType, bytes);
  }

  private void stubObjectUrl() {
    given(s3UploadService.objectUrl(anyString())).willReturn(S3_URL);
  }

  @Test
  @DisplayName("avatar + image/png면 200과 snake_case 봉투(s3_url)를 반환한다")
  void upload_avatarPng_returns200WithSnakeCaseKeys() throws Exception {
    // Given
    stubObjectUrl();

    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("profile.png", "image/png", PNG_BYTES))
            .param("purpose", "avatar")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.s3_url").value(S3_URL));
  }

  @Test
  @DisplayName("report_document + application/pdf면 200을 반환한다(문서는 PDF 허용)")
  void upload_reportDocumentPdf_returns200() throws Exception {
    // Given
    stubObjectUrl();

    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("diagnosis.pdf", "application/pdf", PDF_BYTES))
            .param("purpose", "report_document")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.s3_url").value(S3_URL));
  }

  @Test
  @DisplayName("license + image/png면 200을 반환한다")
  void upload_licensePng_returns200() throws Exception {
    // Given
    stubObjectUrl();

    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("license.png", "image/png", PNG_BYTES))
            .param("purpose", "license")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.s3_url").value(S3_URL));
  }

  @Test
  @DisplayName("registration + image/jpeg면 200을 반환한다")
  void upload_registrationJpeg_returns200() throws Exception {
    // Given
    stubObjectUrl();

    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("registration.jpg", "image/jpeg", JPEG_BYTES))
            .param("purpose", "registration")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.s3_url").value(S3_URL));
  }

  @Test
  @DisplayName("avatar + application/pdf면 400 UPLOAD_CONTENT_TYPE_NOT_ALLOWED")
  void upload_avatarWithPdf_returns400() throws Exception {
    // When & Then — 실제 UploadService 화이트리스트가 거부한다
    mockMvc.perform(multipart("/uploads")
            .file(filePart("doc.pdf", "application/pdf", PDF_BYTES))
            .param("purpose", "avatar")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UPLOAD_CONTENT_TYPE_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("report_document + image/gif면 400 UPLOAD_CONTENT_TYPE_NOT_ALLOWED")
  void upload_reportDocumentWithGif_returns400() throws Exception {
    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("anim.gif", "image/gif", new byte[]{0x47, 0x49, 0x46}))
            .param("purpose", "report_document")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UPLOAD_CONTENT_TYPE_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("선언은 image/png인데 실제 바이트가 PDF면 400 UPLOAD_CONTENT_TYPE_NOT_ALLOWED(위장 탐지)")
  void upload_spoofedPng_returns400() throws Exception {
    // When & Then — 매직바이트 재검증이 위장을 거부한다
    mockMvc.perform(multipart("/uploads")
            .file(filePart("fake.png", "image/png", PDF_BYTES))
            .param("purpose", "avatar")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UPLOAD_CONTENT_TYPE_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("avatar 용량이 5MB를 초과하면 413 UPLOAD_FILE_TOO_LARGE")
  void upload_avatarTooLarge_returns413() throws Exception {
    // Given — 5MB + 1 바이트(선언 image/png). 용량 검증이 내용 검증보다 먼저 거부한다
    byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];

    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("huge.png", "image/png", tooLarge))
            .param("purpose", "avatar")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("UPLOAD_FILE_TOO_LARGE"));
  }

  @Test
  @DisplayName("알 수 없는 purpose(passport)면 400 INVALID_REQUEST(컨버터 변환 실패)")
  void upload_unknownPurpose_returns400() throws Exception {
    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("id.png", "image/png", PNG_BYTES))
            .param("purpose", "passport")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("purpose가 대문자 AVATAR면 400 INVALID_REQUEST(대소문자 무시 안 함)")
  void upload_uppercasePurpose_returns400() throws Exception {
    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("id.png", "image/png", PNG_BYTES))
            .param("purpose", "AVATAR")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("purpose 파라미터가 누락되면 400 MISSING_REQUIRED_FIELD")
  void upload_missingPurpose_returns400() throws Exception {
    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("a.png", "image/png", PNG_BYTES))
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_FIELD"));
  }

  @Test
  @DisplayName("file 파트가 누락되면 400 MISSING_REQUIRED_FIELD")
  void upload_missingFile_returns400() throws Exception {
    // When & Then
    mockMvc.perform(multipart("/uploads")
            .param("purpose", "avatar")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_FIELD"));
  }

  @Test
  @DisplayName("미인증이면 401 LOGIN_REQUIRED")
  void upload_noAuth_returns401() throws Exception {
    // When & Then
    mockMvc.perform(multipart("/uploads")
            .file(filePart("a.png", "image/png", PNG_BYTES))
            .param("purpose", "avatar"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }
}
