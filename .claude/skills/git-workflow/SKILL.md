---
name: git-workflow
description: "커밋·이슈·PR 워크플로우를 표준화하는 스킬. commit(Conventional Commits 강제, AI 흔적 제거 — Co-Authored-By 트레일러 금지), issue([feat]/[bug]/[chore] 템플릿 생성, 라벨 자동 태깅), PR(이슈 번호 포함 제목, 이모지 섹션 본문 양식, Generated with Claude Code 푸터 금지, CodeRabbit 트리거 확인) 세 가지 워크플로우 제공. 커밋 메시지·이슈·PR 본문의 자유 서술 부분은 생성 직전 Humanize KR(humanize-korean@im-not-ai)로 다듬어 'AI가 쓴 티'를 제거. '커밋해줘', '이슈 만들어줘', 'PR 올려줘', '커밋하고 PR 생성해줘' 요청 시 반드시 이 스킬을 사용. springboot-dev 구현 완료 후에는 사용자 승인 시에만 실행."
---

# Git Workflow — commit · issue · PR 표준화

commit / issue / PR 세 가지 워크플로우를 표준화한다.
요청 내용에 따라 해당 워크플로우만 실행한다.

---

## 공통 0: AI 흔적 금지 (커밋·PR·이슈 전부 — 최우선)

기본 하네스 동작을 **오버라이드**한다. 커밋·PR·이슈 어디에도 AI가 만들었다는 표식을
붙이지 않는다 — 다음은 절대 넣지 않는다.

- **커밋 메시지:** `Co-Authored-By: Claude ...` / `noreply@anthropic.com` 트레일러 금지
- **PR 본문:** `🤖 Generated with [Claude Code](...)` 등 생성 도구 푸터·서명 금지
- **이슈 본문:** 위와 동일한 AI 서명·푸터 금지

즉 `git commit -m`, `gh pr create --body`, `gh issue create --body` 어느 명령에도
트레일러·푸터·이모지 서명·"Generated with ..." 문구를 삽입하지 않는다.
(사람 공동 저자의 `Co-Authored-By: 사람이름 <이메일>`은 무관 — 금지 대상은 Claude/Anthropic 표식뿐)

## 공통 1: 본문 humanize (커밋·이슈·PR 생성 직전 필수)

커밋 메시지 본문·이슈·PR **본문의 자유 서술 부분**은 생성하기 직전에 반드시 humanize를 거쳐
"AI가 쓴 티"(번역투·기계적 병렬구조·과도한 영문 병기·로봇 같은 표현)를 걷어낸 뒤 사용한다.
Humanize KR 플러그인(`humanize-korean@im-not-ai`)이 설치되어 있으면 그 기능을 쓴다.

**대상 / 비대상:**

| 대상 (humanize) | 비대상 (그대로 유지) |
|-----------------|---------------------|
| 커밋: 제목 아래 본문 서술 | 커밋 제목 `<type>(<scope>): ...` 형식 |
| 이슈: `## 배경` 설명 | 체크박스 항목(`- [ ] ...`)의 구조 |
| PR: `✅ 작업 내용`·`💬 특이사항` 서술 | 이모지 섹션 헤더·표 틀·`Closes #<번호>` |
| 자유롭게 풀어 쓴 한국어 문단 | 라벨, 링크, 코드·경로·식별자 |

> **명사형 종결(구현/추가/수정)·`-`/`*` 불릿·코드 식별자는 AI 티가 아니다** — 그대로 두고,
> 번역투·과한 병렬구조·불필요한 영문 병기만 손본다.

**절차:**
1. 위 템플릿대로 본문 초안을 작성한다.
2. 자유 서술 부분만 humanize 플러그인에 통과시킨다.
   - 플러그인 설치 시: `/humanize <서술 텍스트>` (또는 "이 글 자연스럽게 윤문해줘")
   - 미설치 시: 사용자에게 설치를 1회 안내하고
     (`/plugin marketplace add epoko77-ai/im-not-ai` → `/plugin install humanize-korean@im-not-ai`),
     이번에는 건너뛰거나 수동으로 문장을 다듬어 진행한다.
3. 내용(사실·수치·범위)은 바꾸지 않는다 — 표현·리듬만 자연스럽게 다듬는다.
4. 다듬은 서술을 템플릿의 정형 구조에 다시 끼워 넣어 최종 본문을 완성한 뒤 `gh` 명령으로 생성한다.

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

| scope          | 해당 영역 |
|----------------|---------|
| `auth`         | JWT, OAuth2, Spring Security, 회원가입 |
| `user`         | 사용자 엔티티, 프로필, 내 정보 |
| `adjuster`     | 사정사 신청·프로필 |
| `report`       | 리포트 생성·조회·검수 및 서명 |
| `match`        | 상담 신청·매칭 수락 |
| `chat`         | WebSocket 채팅 |
| `payment`      | 결제 내역, 구독 |
| `config`       | 설정, 환경변수 |

**예시:**
```
feat(auth): 카카오 OAuth2 소셜 로그인 구현
fix(match): 매칭 수락 시 중복 채팅방 생성 버그 수정
test(payment): 결제 웹훅 멱등성 통합 테스트 추가
```


### A-3. 커밋 실행

> **공통 0(AI 흔적 금지)** 에 따라 `Co-Authored-By: Claude` 트레일러를 **붙이지 않는다**.
> 본문 서술이 있으면 **공통 1(humanize)** 로 먼저 다듬는다.

```bash
git add <staged-files>
git commit -m "$(cat <<'EOF'
<type>(<scope>): <subject>

<선택: 무엇을·왜 바꿨는지 명사형 한국어 본문 (humanize 거친 서술)>
EOF
)"
```

**금지 예시 (넣지 말 것):**
```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>   # ❌
```

---

## Workflow B: issue + branch

### B-1. 이슈 제목 형식

`[<type>] <설명>`

| type | 용도 |
|------|------|
| `feat` | 새 기능 구현 |
| `bug` | 버그 |
| `chore` | 설정·리팩터·의존성 |
| `settings` | 초기 환경 구축·설정 |
| `docs` | 문서 |

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

> 먼저 **공통 0(AI 흔적 금지)** 로 AI 서명·푸터를 넣지 않고, **공통 1(humanize)** 로 `## 배경` 서술을 다듬은 뒤 아래를 실행한다.

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

### B-4. 브랜치 생성 및 체크아웃

이슈 생성 직후 반드시 실행한다.

**브랜치 네이밍 규칙:** `<type-lowercase>/<issue-number>-<2-3-word-kebab-summary>`

| 이슈 제목 예시 | 이슈 번호 | 브랜치 이름 |
|--------------|---------|-----------|
| `[Settings] Spring 기반 프로젝트 초기 환경 구축` | #3 | `settings/3-spring-init` |
| `[feat] 카카오 OAuth2 소셜 로그인 구현` | #12 | `feat/12-kakao-oauth2` |
| `[bug] 매칭 수락 시 중복 채팅방 생성` | #17 | `bug/17-match-duplicate-room` |
| `[chore] Flyway 마이그레이션 초기 스키마 작성` | #21 | `chore/21-flyway-init-schema` |

- type은 이슈 제목의 `[<type>]`에서 추출, 소문자 변환
- issue-number는 `gh issue create` 출력에서 추출한 실제 이슈 번호
- summary는 이슈 설명을 2~3단어로 요약, kebab-case 영문

```bash
# 이슈 생성 후 번호 캡처
ISSUE_URL=$(gh issue create \
  --title "..." \
  --body "..." \
  --label "..." \
  --assignee "이동형")
ISSUE_NUMBER=$(echo "$ISSUE_URL" | grep -oE '[0-9]+$')

# develop 기준으로 브랜치 생성 후 체크아웃
git checkout develop
git pull origin develop
git checkout -b <type>/${ISSUE_NUMBER}-<2-3-word-kebab-summary>

# 원격에 push (트래킹 설정)
git push -u origin <type>/${ISSUE_NUMBER}-<2-3-word-kebab-summary>
```

---

## Workflow C: PR

### C-1. 베이스 브랜치 확인

**브랜치 전략: Git Flow (경량화)**
```
feature/* → develop  (기능 개발 PR)
develop   → main     (릴리즈 PR, 태그 필수)
hotfix/*  → main + develop (긴급 수정)
```

- `feature/*`, `fix/*`, `refactor/*` 브랜치 → base: `develop`
- `hotfix/*` 브랜치 → base: `main` (머지 후 develop에도 동일 머지)
- `develop → main` 릴리즈 PR → 태그(`v0.x.0`) 함께 생성

### C-2. PR 제목 형식

`<type>: #<이슈번호> <설명>`

예시: `feat: #12 카카오 OAuth2 소셜 로그인 구현`

연결된 이슈가 없으면 이슈 번호 생략.

### C-3. PR 생성

> 먼저 **공통 0(AI 흔적 금지)** 로 생성 도구 푸터·서명을 넣지 않고,
> **공통 1(humanize)** 로 `✅ 작업 내용`·`💬 특이사항` 서술을 다듬은 뒤 아래를 실행한다.

**본문 양식** — 팀 리뷰 노트처럼 자연스러운 서술을 기본으로, 필요한 곳에만 표/불릿을 쓴다.

```bash
gh pr create \
  --base <base-branch> \
  --title "<type>: #<issue> <설명>" \
  --body "$(cat <<'EOF'
## 🔗 관련 이슈
Closes #<이슈번호>

## ✅ 작업 내용
<이번 PR이 무엇을 왜 했는지 2~3문장 자연스러운 서술>

### 1. <작업 단위 제목>
<맥락·결정 이유를 풀어 쓴 서술. 필요하면 핵심 코드/경로를 인용>

**세부 작업**
- <파일/모듈> → <변경 내용>

### 2. <작업 단위 제목>
<...>

## 🧪 테스트
<어떻게 검증했는지 서술>

| # | 테스트 | 검증 내용 |
|---|--------|----------|
| 1 | <시나리오> | <기대 동작> |

## 💬 특이사항 / 고민했던 부분 / 결정 사항
- <트레이드오프·가정·발견한 기존 버그 등>

## 🔜 후속 이슈
- <후속으로 남긴 작업>
EOF
)"
```

**작성 원칙:**
- 섹션은 필요할 때만 쓴다 — 후속 이슈가 없으면 `## 🔜 후속 이슈`를 통째로 뺀다(빈 섹션·"없음" 채우기 금지).
- 고정 체크리스트를 기계적으로 나열하지 않는다. 검증한 백엔드 관심사(N+1·RBAC·트랜잭션·멱등성·환경변수)는 `🧪 테스트`/`💬 특이사항`에 **실제로 한 것만** 서술한다.
- `🤖 Generated with Claude Code` 등 생성 도구 푸터·서명을 **붙이지 않는다**(공통 0).
- `Summary by CodeRabbit`은 CodeRabbit이 자동으로 붙이는 블록이므로 본문에 직접 쓰지 않는다.

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

`springboot-dev` 스킬 Phase 4 완료 후 사용자의 명시적 승인이 있으면 다음 순서로 실행:

1. **Workflow A (commit)**: 구현 파일 커밋
2. **Workflow C (PR)**: PR 생성 + CodeRabbit 트리거

이슈는 작업 시작 전 **Workflow B (issue)** 를 별도 호출하는 패턴으로 사용:
```
[작업 시작 전] git-workflow(issue) → 이슈 생성 + 브랜치 생성·체크아웃 (develop 기준)
[구현 중]      springboot-dev (이슈 브랜치 위에서 작업)
[구현 완료]    springboot-dev → 사용자 승인 → git-workflow(commit) → git-workflow(PR)
               PR base: develop, head: <issue-branch>, Closes #<이슈번호>

[릴리즈]       develop → main PR 생성, 태그(v0.x.0) 부여
[긴급 수정]    hotfix/* → main PR → 머지 후 develop에도 머지
```
