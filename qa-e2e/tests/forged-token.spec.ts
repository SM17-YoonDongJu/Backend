import { test } from '@playwright/test';
import { expectErrorResponse, FORGED_JWT, send, type Method } from './helpers/api';

// QA: 위조(서명 조작) access_token 쿠키 → 401 INVALID_TOKEN.
// 참고: SEC-07/USER-05의 '만료 토큰(EXPIRED_TOKEN)'은 유효 서명 + 과거 exp가 필요해
//       JWT_SECRET 없이는 만들 수 없다 → 여기서는 위조(INVALID_TOKEN)만 다룬다.
const CASES: { id: string; method: Method; path: string }[] = [
  { id: 'USER-05', method: 'get', path: '/users/me' },
  { id: 'SEC-08', method: 'get', path: '/users/me' },
];

test.describe('위조 토큰 → 401 INVALID_TOKEN', () => {
  for (const c of CASES) {
    test(`[${c.id}] ${c.method.toUpperCase()} ${c.path}`, async ({ playwright, baseURL }) => {
      const ctx = await playwright.request.newContext({
        baseURL,
        extraHTTPHeaders: { Cookie: `access_token=${FORGED_JWT}` },
      });
      try {
        const res = await send(ctx, c.method, c.path);
        await expectErrorResponse(res, 401, 'INVALID_TOKEN');
      } finally {
        await ctx.dispose();
      }
    });
  }
});
