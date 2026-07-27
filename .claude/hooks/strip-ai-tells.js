// PreToolUse(Bash) 가드: 커밋·PR·이슈에 AI 생성 흔적이 들어가는 것을 차단한다.
// CLAUDE.md Git Conventions / git-workflow 스킬의 "AI 흔적 금지(공통 0)"를 강제한다.
// 금지 대상은 Claude/Anthropic 표식뿐 — 사람 공동 저자의 Co-Authored-By는 허용한다.
// 차단은 exit code 2 + stderr. 판단 불가 시 fail-open(exit 0)으로 정상 작업을 막지 않는다.
const fs = require('fs');

function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8');
  } catch (err) {
    return '';
  }
}

function deny(message) {
  process.stderr.write(message + '\n');
  process.exit(2);
}

let data = {};
try {
  data = JSON.parse(readStdin() || '{}');
} catch (err) {
  process.exit(0);
}

const cmd = (data.tool_input && data.tool_input.command) || '';
if (!cmd) {
  process.exit(0);
}

// 커밋 / PR·이슈 생성·수정 명령에만 관여한다.
const isCommit = /\bgit\s+commit\b/.test(cmd);
const isGh = /\bgh\s+(pr|issue)\s+(create|edit)\b/.test(cmd);
if (!isCommit && !isGh) {
  process.exit(0);
}

// 실제 트레일러·푸터의 "구조적" 형태만 매칭한다 (본문에서 단어로 언급하는 것은 허용).
// 예: 트레일러는 항상 <이메일>을 동반하고, 푸터는 [Claude Code](url)/🤖/도메인 URL 형태다.
// 이렇게 좁혀야 "Co-Authored-By 트레일러를 제거한다" 같은 서술이 오탐으로 막히지 않는다.
const AI_TELLS = [
  /Co-?Authored-?By:\s*Claude[^\n<]*<[^>]*>/i,
  /noreply@anthropic\.com/i,
  /Generated\s+with\s+\[Claude\s+Code\]/i,
  /🤖\s*Generated\s+with/i,
  /claude\.(com|ai)\/claude-code/i,
];

const hit = AI_TELLS.find((re) => re.test(cmd));
if (hit) {
  deny(
    '⛔ 커밋/PR/이슈에 AI 생성 흔적을 넣지 않는다 (CLAUDE.md Git Conventions · git-workflow 공통 0).\n' +
      '   "Co-Authored-By: Claude" · noreply@anthropic.com · "🤖 Generated with Claude Code" 푸터를 제거하고 다시 시도하세요.'
  );
}

process.exit(0);
