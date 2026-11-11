import { APIRequestContext, APIResponse, expect } from '@playwright/test';

/** 서명이 조작된 가짜 JWT — JwtProvider 검증을 반드시 실패시킨다(INVALID_TOKEN 유도). */
export const FORGED_JWT =
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.' +
  'eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAiLCJyb2xlIjoiVVNFUiJ9.' +
  'ThisSignatureIsForgedAndWillNeverVerify';

export type Method = 'get' | 'post' | 'patch' | 'put' | 'delete';

/** 테이블 드리븐 호출 헬퍼 — 메서드 문자열로 APIRequestContext를 호출한다. */
export function send(
  ctx: APIRequestContext,
  method: Method,
  path: string,
  body?: unknown,
): Promise<APIResponse> {
  const options = body !== undefined ? { data: body } : undefined;
  switch (method) {
    case 'get':
      return ctx.get(path, options);
    case 'post':
      return ctx.post(path, options);
    case 'patch':
      return ctx.patch(path, options);
    case 'put':
      return ctx.put(path, options);
    case 'delete':
      return ctx.delete(path, options);
    default:
      throw new Error(`지원하지 않는 method: ${method}`);
  }
}

/** ErrorResponse 계약 검증: HTTP status + { status, code, message } + JSON(UTF-8). */
export async function expectErrorResponse(
  res: APIResponse,
  httpStatus: number,
  code: string,
): Promise<void> {
  expect(res.status(), `HTTP ${httpStatus} 기대(실제 ${res.status()})`).toBe(httpStatus);
  expect(res.headers()['content-type'] ?? '', 'Content-Type application/json').toContain(
    'application/json',
  );
  const body = await res.json();
  expect(body, 'ErrorResponse 형태 { status, code, message }').toMatchObject({
    status: String(httpStatus),
    code,
  });
  expect(typeof body.message, 'message 문자열 존재').toBe('string');
}

/** 객체/배열의 모든 키를 재귀 수집(전역 snake_case 검증용). */
export function collectKeys(value: unknown, acc: string[] = []): string[] {
  if (Array.isArray(value)) {
    value.forEach((item) => collectKeys(item, acc));
  } else if (value && typeof value === 'object') {
    for (const key of Object.keys(value as Record<string, unknown>)) {
      acc.push(key);
      collectKeys((value as Record<string, unknown>)[key], acc);
    }
  }
  return acc;
}
