---
name: git-workflow
description: "커밋·이슈·PR 워크플로우를 표준화하는 스킬. commit(Conventional Commits 강제, Co-Authored-By 추가), issue([feat]/[bug]/[chore] 템플릿 생성, 라벨 자동 태깅), PR(이슈 번호 포함 제목, 본문 템플릿, CodeRabbit 트리거 확인) 세 가지 워크플로우 제공. '커밋해줘', '이슈 만들어줘', 'PR 올려줘', '커밋하고 PR 생성해줘' 요청 시 반드시 이 스킬을 사용. springboot-dev 구현 완료 후 자동 호출."
---

# Git Workflow — commit · issue · PR 표준화

commit / issue / PR 세 가지 워크플로우를 표준화한다.
요청 내용에 따라 해당 워크플로우만 실행한다.

---

## Workflow A: commit

### A-1. 변경 파일 확인

```bash
git status
git diff --stat HEAD
```

**범위 초과 판단 기준:**
- 서로 다른 기능 도메인(예: auth + matching)이 한 커밋에 섞인 경우 → 분리 제안
- 파일이 10개 초과이고 변경 이유가 하나로 설명되지 않는 경우 → 분리 제안
- 분리가 필요하면 커밋 단위 제안 목록을 출력하고 사용자 확인 후 진행

### A-2. 커밋 메시지 작성

**형식:** `<type>(<scope>): <subject>`

| type | 용도 |
|------|------|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 코드 개선 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드·설정·의존성 변경 |
| `docs` | 문서 변경 |

**scope 가이드 (이 프로젝트):**

| scope      | 해당 영역 |
|------------|---------|
| `auth`     | JWT, OAuth2, Spring Security |
| `adjuster` | 검수 리포트, review_feedback |
| `chat`     | WebSocket 채팅, 챗봇 |
| `user`     | 사용자 엔티티, 프로필 |
| `config`   | 설정, 환경변수 |

**예시:**
```
feat(auth): 카카오 OAuth2 소셜 로그인 구현
fix(matching): 매칭 수락 시 중복 채팅방 생성 버그 수정
test(payment): 결제 웹훅 멱등성 통합 테스트 추가
```


### A-3. 커밋 실행

```bash
git add <staged-files>
git commit -m "$(cat <<'EOF'
<type>(<scope>): <subject>

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Workflow B: issue

### B-1. 이슈 제목 형식

`[<type>] <설명>`

| type | 용도 |
|------|------|
| `feat` | 새 기능 구현 |
| `bug` | 버그 |
| `chore` | 설정·리팩터·의존성 |

예시: `[feat] 카카오 OAuth2 소셜 로그인 구현`

### B-2. 라벨 자동 태깅 기준

| 키워드 (요청 또는 변경 파일) | 라벨                |
|--------------------------|-------------------|
| auth, JWT, OAuth2, security | `spring-security` |
| WebSocket, STOMP, 채팅, chatbot | `realtime`        |
| Redis | `redis`           |
| 리포트, report, feedback | `adjuster`        |
| test, 테스트 | `test`            |
| 공통, config, 설정 | `chore`           |

### B-3. 이슈 생성

```bash
gh issue create \
  --title "[<type>] <설명>" \
  --body "$(cat <<'EOF'
## 배경
<이 작업이 필요한 이유>

## 구현 범위
- [ ] <항목1>
- [ ] <항목2>

## 완료 조건 (Acceptance Criteria)
- [ ] <AC1>
- [ ] <AC2>

## 참고 문서
- <링크 또는 파일 경로>
EOF
)" \
  --label "<라벨1>,<라벨2>" \
  --assignee "이동형"
```

---

## Workflow C: PR

### C-1. 베이스 브랜치 확인

```bash
git branch -a | grep -E "main|develop"
```

- `develop` 브랜치가 있으면 → base: `develop`
- 없으면 → base: `main`

### C-2. PR 제목 형식

`<type>: #<이슈번호> <설명>`

예시: `feat: #12 카카오 OAuth2 소셜 로그인 구현`

연결된 이슈가 없으면 이슈 번호 생략.

### C-3. PR 생성

```bash
gh pr create \
  --base <base-branch> \
  --title "<type>: #<issue> <설명>" \
  --body "$(cat <<'EOF'
## Summary
- <변경 내용 1줄 요약>

## 변경 내역
- <파일/모듈별 변경 설명>

## 테스트 방법
- [ ] <테스트 항목1>
- [ ] <테스트 항목2>

## 체크리스트
- [ ] Conventional Commits 형식 준수
- [ ] 테스트 코드 작성
- [ ] 환경변수 하드코딩 없음
- [ ] N+1 쿼리 없음 (JPA 변경 시)
- [ ] RBAC 권한 검증 완료

Closes #<이슈번호>
EOF
)"
```

### C-4. CodeRabbit 리뷰 확인

PR 생성 후:
```bash
# PR 번호 확인
gh pr view --json number

# CodeRabbit 리뷰 대기 (수 분 소요)
# 리뷰가 오지 않으면 수동 트리거
gh pr comment <pr_number> --body "@coderabbitai review"
```

CodeRabbit 리뷰가 완료되면 coderabbit-review 스킬을 사용하여 지적 사항을 반영한다.

---

## springboot-dev 연동

`springboot-dev` 스킬 Phase 4 완료 후 자동으로 다음 순서로 실행:

1. **Workflow A (commit)**: 구현 파일 커밋
2. **Workflow C (PR)**: PR 생성 + CodeRabbit 트리거

이슈는 작업 시작 전 **Workflow B (issue)** 를 별도 호출하는 패턴으로 사용:
```
[작업 시작 전] git-workflow(issue) → 이슈 생성
[구현 중] springboot-dev
[구현 완료] springboot-dev → git-workflow(commit) → git-workflow(PR)
```
