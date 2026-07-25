package com.soma.backend.domain.upload.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import com.soma.backend.infra.s3.PresignedUploadService;

/**
 * UploadController 슬라이스 테스트. 실제 UploadService·UploadPurpose·@Valid·GlobalExceptionHandler를
 * 시큐리티 체인과 함께 배선하고 S3 경계(PresignedUploadService)만 @MockitoBean으로 대체한다. purpose×
 * content_type 교차 검증, 미지 purpose 역직렬화, 필드 검증, 인증 경계, snake_case 응답 봉투를 검증한다.
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

  private static final String UPLOAD_URL =
      "https://test-bucket.s3.ap-northeast-2.amazonaws.com/avatars/uuid.png?X-Amz-Signature=abc";
  private static final String S3_URL =
      "https://test-bucket.s3.ap-northeast-2.amazonaws.com/avatars/uuid.png";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PresignedUploadService presignedUploadService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private void stubPresigner() {
    given(presignedUploadService.presignPut(anyString(), anyString(), any(Duration.class)))
        .willReturn(UPLOAD_URL);
    given(presignedUploadService.objectUrl(anyString())).willReturn(S3_URL);
  }

  private static String body(String fileName, String contentType, String purpose) {
    return "{\"file_name\":\"" + fileName + "\",\"content_type\":\"" + contentType
        + "\",\"purpose\":\"" + purpose + "\"}";
  }

  @Test
  @DisplayName("POST /uploads-avatar+image/webp면 200과 snake_case 봉투(upload_url·s3_url)를 반환한다")
  void createUploadUrl_avatarWebp_returns200WithSnakeCaseKeys() throws Exception {
    // Given
    stubPresigner();

    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("profile.webp", "image/webp", "avatar")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.upload_url").value(UPLOAD_URL))
        .andExpect(jsonPath("$.data.s3_url").value(S3_URL));
  }

  @Test
  @DisplayName("POST /uploads-report_document+application/pdf면 200을 반환한다(문서는 PDF 허용)")
  void createUploadUrl_reportDocumentPdf_returns200() throws Exception {
    // Given
    stubPresigner();

    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("diagnosis.pdf", "application/pdf", "report_document")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.upload_url").value(UPLOAD_URL))
        .andExpect(jsonPath("$.data.s3_url").value(S3_URL));
  }

  @Test
  @DisplayName("POST /uploads-license+image/png면 200을 반환한다")
  void createUploadUrl_licensePng_returns200() throws Exception {
    // Given
    stubPresigner();

    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("license.png", "image/png", "license")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.s3_url").value(S3_URL));
  }

  @Test
  @DisplayName("POST /uploads-registration+image/jpeg면 200을 반환한다")
  void createUploadUrl_registrationJpeg_returns200() throws Exception {
    // Given
    stubPresigner();

    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("registration.jpg", "image/jpeg", "registration")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.s3_url").value(S3_URL));
  }

  @Test
  @DisplayName("POST /uploads-avatar+application/pdf면 400 UPLOAD_CONTENT_TYPE_NOT_ALLOWED")
  void createUploadUrl_avatarWithPdf_returns400() throws Exception {
    // When & Then — 실제 UploadService 화이트리스트가 거부한다
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("doc.pdf", "application/pdf", "avatar")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UPLOAD_CONTENT_TYPE_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("POST /uploads-report_document+image/gif면 400 UPLOAD_CONTENT_TYPE_NOT_ALLOWED")
  void createUploadUrl_reportDocumentWithGif_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("anim.gif", "image/gif", "report_document")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UPLOAD_CONTENT_TYPE_NOT_ALLOWED"));
  }

  @Test
  @DisplayName("POST /uploads-알 수 없는 purpose(passport)면 400 INVALID_REQUEST(@JsonCreator 역직렬화 실패)")
  void createUploadUrl_unknownPurpose_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("id.png", "image/png", "passport")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("POST /uploads-purpose가 대문자 AVATAR면 400 INVALID_REQUEST(대소문자 무시 안 함)")
  void createUploadUrl_uppercasePurpose_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("id.png", "image/png", "AVATAR")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("POST /uploads-file_name이 공백이면 400 BAD_REQUEST(@Valid)")
  void createUploadUrl_blankFileName_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("", "image/png", "avatar")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("POST /uploads-content_type이 공백이면 400 BAD_REQUEST(@Valid, 화이트리스트보다 먼저)")
  void createUploadUrl_blankContentType_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("a.png", "", "avatar")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("POST /uploads-purpose가 누락되면 400 BAD_REQUEST(@NotNull)")
  void createUploadUrl_missingPurpose_returns400() throws Exception {
    // Given — purpose 필드를 생략한 body
    String bodyWithoutPurpose = "{\"file_name\":\"a.png\",\"content_type\":\"image/png\"}";

    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(bodyWithoutPurpose))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("POST /uploads-비-JSON 본문이면 400 INVALID_REQUEST(HttpMessageNotReadable)")
  void createUploadUrl_nonJsonBody_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/uploads").with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("POST /uploads-미인증이면 401 LOGIN_REQUIRED")
  void createUploadUrl_noAuth_returns401() throws Exception {
    // When & Then
    mockMvc.perform(post("/uploads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("a.png", "image/png", "avatar")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }
}
