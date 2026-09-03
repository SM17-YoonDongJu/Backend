import { test, expect } from '@playwright/test';
import { collectKeys } from './helpers/api';

// 2단계: setup 이 저장한 storageState(dev-login 세션)를 재사용하는 인증 필요 항목.
test.describe('인증 세션 (dev-login storageState)', () => {
  test('[SEC-01] 유효 세션 → 보호 API 200 + ApiResponse 래핑', async ({ request }) => {
    const res = await request.get('/users/me');
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body, '성공 응답 { status:200, message, data }').toMatchObject({ status: '200' });
    expect(body).toHaveProperty('data');
  });

  test('[FMT-03] 전역 snake_case 직렬화(응답 키에 대문자 없음)', async ({ request }) => {
    const res = await request.get('/users/me');
    const body = await res.json();
    const camelKeys = collectKeys(body).filter((key) => /[A-Z]/.test(key));
    expect(camelKeys, `camelCase 키 발견: ${camelKeys.join(', ')}`).toHaveLength(0);
  });

  test('[SEC-03] USER 세션 → 사정사 전용 API 접근 차단', async ({ request }) => {
    // dev-login 기본 유저 role 에 따라 달라진다(USER 기준 403 기대). 401 은 세션 미적용 신호.
    const res = await request.get('/adjusters/me/home');
    expect([403, 401], `실제=${res.status()}`).toContain(res.status());
  });
});
