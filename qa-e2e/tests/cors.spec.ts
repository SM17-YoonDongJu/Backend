import { test, expect } from '@playwright/test';

// QA FMT-08 / FMT-09: 쿠키 인증이라 CORS는 allowCredentials(true) + allowedOriginPatterns 목록.
// 와일드카드(*) 불가 — 등록된 오리진만 Access-Control-Allow-Origin 으로 에코된다.
const ALLOWED_ORIGIN =
  process.env.ALLOWED_ORIGIN ?? 'https://frontend-web-psi-two.vercel.app';

test.describe('CORS 오리진 정책', () => {
  test('[FMT-08] 미등록 오리진(evil.com) 프리플라이트 → ACAO 미반환', async ({
    playwright,
    baseURL,
  }) => {
    const ctx = await playwright.request.newContext({ baseURL });
    try {
      const res = await ctx.fetch('/users/me', {
        method: 'OPTIONS',
        headers: {
          Origin: 'https://evil.com',
          'Access-Control-Request-Method': 'GET',
        },
      });
      expect(
        res.headers()['access-control-allow-origin'],
        'evil.com 은 ACAO 로 에코되면 안 됨',
      ).not.toBe('https://evil.com');
    } finally {
      await ctx.dispose();
    }
  });

  test('[FMT-09] 허용 오리진(vercel) 프리플라이트 → ACAO + 자격증명 허용', async ({
    playwright,
    baseURL,
  }) => {
    const ctx = await playwright.request.newContext({ baseURL });
    try {
      const res = await ctx.fetch('/users/me', {
        method: 'OPTIONS',
        headers: {
          Origin: ALLOWED_ORIGIN,
          'Access-Control-Request-Method': 'GET',
        },
      });
      expect(res.headers()['access-control-allow-origin']).toBe(ALLOWED_ORIGIN);
      expect(res.headers()['access-control-allow-credentials']).toBe('true');
    } finally {
      await ctx.dispose();
    }
  });
});
