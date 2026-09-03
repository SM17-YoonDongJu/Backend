import { test as setup, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

const AUTH_FILE = path.join('.auth', 'user.json');

/**
 * dev-login 으로 인증 세션(access_token 쿠키)을 확보해 storageState 로 저장한다.
 *
 * ⚠️ POST /auth/dev/login 은 app.dev-login.enabled=true(로컬 프로파일)에서만 빈으로 등록된다.
 *    원격 dev/prod 백엔드에는 경로가 없으므로(ConditionalOnProperty), 그 환경에서는 이 setup 대신
 *    브라우저에서 수동 로그인 후 아래 형태로 .auth/user.json 을 직접 채운다(README '인증 세션' 참고):
 *      { "cookies": [{ "name": "access_token", "value": "<복사한 값>", "domain": "<host>",
 *                      "path": "/", "expires": -1, "httpOnly": true, "secure": true,
 *                      "sameSite": "Lax" }], "origins": [] }
 */
setup('dev-login 세션 확보', async ({ request }) => {
  const res = await request.post('/auth/dev/login', {
    data: { nickname: process.env.DEV_LOGIN_NICKNAME ?? 'qa-user' },
  });
  expect(
    res.status(),
    'dev-login 200 실패 — 로컬 백엔드 + app.dev-login.enabled=true 인지 확인(원격이면 수동 세션 사용)',
  ).toBe(200);

  fs.mkdirSync('.auth', { recursive: true });
  await request.storageState({ path: AUTH_FILE });
});
