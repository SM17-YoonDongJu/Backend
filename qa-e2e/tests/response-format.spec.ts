import { test, expect } from '@playwright/test';
import { expectErrorResponse } from './helpers/api';

// QA SEC-11 / FMT-04 / EXC-05: 전역 응답 포맷 계약.
test.describe('전역 응답 포맷', () => {
  test('[SEC-11][FMT-04] 미인증 401 바디가 ErrorResponse 계약을 지킴', async ({ request }) => {
    const res = await request.get('/users/me');
    await expectErrorResponse(res, 401, 'LOGIN_REQUIRED');
  });

  test('[EXC-05] 존재하지 않는 경로 → 실제 상태코드 기록(404 기대, catch-all 500 변질 감시)', async ({
    request,
  }) => {
    const res = await request.get('/no-such-endpoint-xyz');
    // 결함 후보: 전역 핸들러가 NoResourceFoundException 을 잡아 500 으로 바꿀 수 있음.
    // 미인증 상태에선 인증 필터가 먼저 401 을 줄 수도 있어 둘 다 허용하되, 500 이면 결함으로 본다.
    expect([404, 401], `실제 상태코드=${res.status()} (500이면 catch-all 결함)`).toContain(
      res.status(),
    );
  });
});
