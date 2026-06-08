---
name: coderabbit-review
description: "CodeRabbit GitHub PR 리뷰 결과를 조회하고 지적 사항을 코드에 반영하는 스킬. PR 리뷰, CodeRabbit 코멘트 반영, PR 코드 개선 요청 시 반드시 이 스킬을 사용."
---

# CodeRabbit Review — PR 리뷰 결과 반영

CodeRabbit이 GitHub PR에 남긴 리뷰 코멘트를 조회하고, 지적 사항을 소스 코드에 반영한다.

## 전제 조건
- GitHub CLI (`gh`) 인증 완료
- CodeRabbit이 해당 저장소에 설치되어 있고 PR에 리뷰 완료 상태

## 워크플로우

### Step 1: PR 번호 확인

현재 브랜치의 PR 번호를 확인한다:
```bash
gh pr list --head $(git branch --show-current) --json number,title,state
```

PR이 없으면 생성 후 CodeRabbit 리뷰 완료까지 대기한다:
```bash
gh pr create --title "..." --body "..."
# CodeRabbit은 PR 생성 후 수 분 내 자동 리뷰
```

### Step 2: CodeRabbit 리뷰 코멘트 조회

PR의 모든 리뷰 코멘트를 가져온다:
```bash
gh api repos/{owner}/{repo}/pulls/{pr_number}/reviews
gh api repos/{owner}/{repo}/pulls/{pr_number}/comments
```

또는 gh pr view로 요약 조회:
```bash
gh pr view {pr_number} --comments
```

CodeRabbit 코멘트 필터링 기준:
- 리뷰어 이름: `coderabbitai[bot]` 또는 `coderabbit-ai`
- 코멘트 바디에 `## Summary`, `### Walkthrough` 등 CodeRabbit 형식 포함

### Step 3: 코멘트 분류

조회된 코멘트를 심각도별로 분류한다:

| 심각도 | 판단 기준 | 처리 |
|--------|---------|------|
| CRITICAL | 버그, 보안 취약점, 데이터 손실 위험 | 반드시 수정 |
| WARNING | 성능 문제, 코드 품질, 잠재적 오류 | 수정 권장 |
| INFO | 스타일, 가독성, 제안 | 판단 후 선택 적용 |

### Step 4: 지적 사항 수정

각 코멘트에 대해:
1. 지적된 파일과 라인을 Read로 확인
2. 수정 사항을 이해하고 Edit으로 반영
3. 수정이 다른 코드에 영향을 주는지 확인

CRITICAL 이슈 발견 시 즉시 리더(또는 사용자)에게 알리고 수정 전 확인 요청.

### Step 5: 리뷰 반영 결과 보고

`_workspace/03_qa/coderabbit-report.md`에 기록:
```markdown
## CodeRabbit 리뷰 반영 결과

### 반영된 지적 사항
| 파일 | 라인 | 심각도 | 내용 | 처리 결과 |
|------|------|--------|------|---------|

### 미반영 항목 (사유 포함)
| 파일 | 라인 | 내용 | 미반영 사유 |
```

### Step 6: 수정 사항 커밋 및 푸시

```bash
git add -p  # 수정 파일만 선택적 스테이징
git commit -m "fix: CodeRabbit 리뷰 반영 - {요약}"
git push
```

푸시 후 CodeRabbit이 재리뷰하면 Step 2부터 반복 (최대 2회).

## 에러 핸들링

| 상황 | 처리 |
|------|------|
| PR 없음 | Step 1에서 PR 생성 후 진행 |
| CodeRabbit 리뷰 미완료 | `@coderabbitai review` 코멘트로 수동 트리거 후 대기 |
| 조회한 코멘트가 0개 | CodeRabbit 설치 여부 확인 후 수동 코드 리뷰로 대체 |
| 수정 방향 불명확 | 수정 대신 PR에 질문 코멘트 추가, 리더에게 판단 요청 |

## CodeRabbit 수동 트리거
리뷰가 자동으로 오지 않을 때:
```bash
gh pr comment {pr_number} --body "@coderabbitai review"
```
