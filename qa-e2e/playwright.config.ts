import { defineConfig } from '@playwright/test';
import 'dotenv/config';

// 백엔드 API base URL. 프론트(vercel)가 호출하는 서버 주소를 .env(API_BASE_URL)로 주입한다.
// 컨트롤러는 /api/v1 prefix 없이 루트 기준(/auth, /users, /reports, /chats ...)이다.
const BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [
    ['list'],
    ['html', { open: 'never' }],
    ['json', { outputFile: 'results.json' }],
  ],
  use: {
    baseURL: BASE_URL,
    // 실패 응답 본문을 트레이스에 남긴다.
    trace: 'retain-on-failure',
  },
  projects: [
    {
      // 1단계: 로그인 없이 도는 항목 (미인증 401 · 위조 토큰 · CORS · 응답 포맷)
      name: 'unauthenticated',
      testMatch: /(unauth|forged-token|cors|response-format)\.spec\.ts/,
    },
    {
      // 2단계 준비: dev-login으로 세션(storageState) 확보
      name: 'setup',
      testMatch: /auth\.setup\.ts/,
    },
    {
      // 2단계: 확보한 세션으로 인증 필요한 항목 검증
      name: 'authenticated',
      testMatch: /auth-session\.spec\.ts/,
      dependencies: ['setup'],
      use: { storageState: '.auth/user.json' },
    },
  ],
});
