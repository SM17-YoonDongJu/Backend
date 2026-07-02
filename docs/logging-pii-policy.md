# 로깅 PII 보호 정책

손해사정 도메인은 민감정보(주민등록번호, 의료정보=진단서, 사고 내역, 결제정보)를 다루므로
로그에 개인식별정보(PII)가 남지 않도록 아래 규칙을 강제한다.

## 절대 로깅 금지 항목 (평문)

| 분류 | 예시 |
|------|------|
| 인증정보 | JWT(Access/Refresh), 비밀번호, OAuth 토큰, `Authorization` 헤더 |
| 고유식별 | 주민등록번호, 여권번호, 외국인등록번호 |
| 연락처 | 휴대폰번호, 이메일 전체, 주소 |
| 의료정보 | 진단서 원문/OCR 결과, 상병명, 진료 내역 |
| 금융정보 | 카드번호, 계좌번호, 결제 승인 응답 원문 |
| 요청 본문 | Controller 요청/응답 body 전체 덤프 |

## 강제 설정 (코드로 보장)

1. **SQL 바인드 파라미터 억제** — `application.yml`
   - `org.hibernate.SQL: warn`, `org.hibernate.orm.jdbc.bind: warn`
   - `spring.jpa.show-sql` 사용 금지 (바인드 값 노출)
2. **로그 패턴 고정** — `logback-spring.xml`
   - body/header 포함 안 함, 표준 패턴만 사용
   - `CommonsRequestLoggingFilter` 등 요청 본문 로깅 필터 사용 금지 (`warn` 이상)
3. **Actuator** — `health`/`info`만 노출, `health.show-details: never`
   (env·configprops·heapdump 등 민감 엔드포인트 미노출)

## 개발자 준수 사항

- 예외 로깅 시 사용자 입력을 그대로 찍지 말 것. 식별자는 UUID(userId) 등 비식별 키만 사용.
- 부득이 식별정보를 남겨야 하면 마스킹(`010-****-1234`, 주민번호 뒷자리 제거) 후 기록.
- `log.info("req={}", requestDto)` 처럼 DTO 전체를 찍지 말 것 (`toString`에 PII 포함 가능).
- 새 도메인 추가 시 이 문서에 민감필드를 갱신한다.
