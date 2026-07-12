// PreToolUse(Bash) 가드: main/develop 직접 commit·merge·rebase·push 차단 (CLAUDE.md 브랜치 전략).
// 차단은 exit code 2 + stderr. 판단 불가 시 fail-open(exit 0)으로 정상 작업을 막지 않는다.
const fs = require('fs');
const { execSync } = require('child_process');

const PROTECTED = ['main', 'develop'];

function readStdin() {
  try {
    return fs.readFileSync(0, 'utf8');
  } catch (err) {
    return '';
  }
}

function currentBranch() {
  try {
    return execSync('git rev-parse --abbrev-ref HEAD', { encoding: 'utf8' }).trim();
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
if (!cmd || !/(^|[\s;&|(])git\s/.test(cmd)) {
  process.exit(0);
}

const branch = currentBranch();
const tokens = cmd.split(/\s+/);

if (/\bgit\s+push\b/.test(cmd)) {
  const explicitProtected = tokens.some((tok) => {
    const ref = tok.includes(':') ? tok.split(':').pop() : tok;
    return PROTECTED.includes(ref);
  });
  const afterPush = cmd.replace(/^[\s\S]*\bgit\s+push\b/, '').trim();
  const nonFlagArgs = afterPush.split(/\s+/).filter((arg) => arg && !arg.startsWith('-'));
  const barePush = nonFlagArgs.length <= 1;
  if (explicitProtected) {
    deny('⛔ main/develop 직접 push 금지 (CLAUDE.md 브랜치 전략). 작업 브랜치(feature/*·chore/*)에서 PR로 진행하세요.');
  }
  if (barePush && PROTECTED.includes(branch)) {
    deny(`⛔ 현재 브랜치가 '${branch}' 입니다. main/develop 직접 push 금지 — 작업 브랜치에서 PR로 진행하세요.`);
  }
}

if (PROTECTED.includes(branch)) {
  if (/\bgit\s+(commit|merge|rebase)\b/.test(cmd) || /\bgit\s+reset\s+--hard\b/.test(cmd)) {
    deny(`⛔ '${branch}' 브랜치에서 직접 commit/merge/rebase 금지 (main/develop 직접 변경 금지). 작업 브랜치를 파서 진행하세요.`);
  }
}

process.exit(0);
