import { test } from '@playwright/test';
import { expectErrorResponse, send, type Method } from './helpers/api';

// QA '결과=미확인' 중 인증 불필요(미인증 → 401 LOGIN_REQUIRED) 항목.
// 쿠키 없이 요청하면 JwtFilter가 401을 직접 응답한다(anonymous 비활성).
const CASES: { id: string; method: Method; path: string; body?: unknown }[] = [
  { id: 'USER-04', method: 'get', path: '/users/me' },
  { id: 'USER-18', method: 'patch', path: '/users/me', body: {} },
  { id: 'USER-23', method: 'delete', path: '/users/me' },
  { id: 'ACT-63', method: 'get', path: '/users/me/activity-summary' },
  { id: 'RPT-07', method: 'post', path: '/reports', body: {} },
  { id: 'PRV-25', method: 'get', path: '/reports/pending-review/summary' },
  { id: 'PRV-50', method: 'get', path: '/adjusters/me/reviewed-reports' },
  { id: 'CHAT-05', method: 'get', path: '/chats' },
  { id: 'NOTI-04', method: 'get', path: '/users/me/notifications' },
  { id: 'NOTI-24', method: 'patch', path: '/users/me/notification-settings', body: {} },
  { id: 'SEC-06', method: 'get', path: '/users/me' },
];

test.describe('미인증 접근 → 401 LOGIN_REQUIRED', () => {
  for (const c of CASES) {
    test(`[${c.id}] ${c.method.toUpperCase()} ${c.path}`, async ({ request }) => {
      const res = await send(request, c.method, c.path, c.body);
      await expectErrorResponse(res, 401, 'LOGIN_REQUIRED');
    });
  }
});
