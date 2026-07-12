package com.soma.backend.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * app.docs.public=true(=dev 프로파일)일 때 Scalar 문서 경로가 인증 없이 열리는지 검증한다.
 * 개방 범위는 문서 경로로 한정되어야 하므로, 그 외 보호 API는 여전히 401이어야 한다.
 */
@SpringBootTest(properties = "app.docs.public=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocsAccessSecurityTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("app.docs.public=true면 미인증으로 Scalar 문서·OpenAPI JSON 접근 허용")
  void docsOpenWhenPublic() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    mockMvc.perform(get("/scalar.html")).andExpect(status().isOk());
    mockMvc.perform(get("/docs")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("문서를 개방해도 보호 API는 여전히 401 (개방 범위가 docs로 한정)")
  void protectedApiStillBlocked() throws Exception {
    mockMvc.perform(get("/reports")).andExpect(status().isUnauthorized());
  }
}
