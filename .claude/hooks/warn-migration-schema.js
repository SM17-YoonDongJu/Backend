// PostToolUse(Edit|Write) soft 경고: 신규 Flyway 마이그레이션의 CREATE TABLE/INDEX·RENAME·
// ADD CONSTRAINT UNIQUE가 core 스키마를 명시하지 않으면 리마인더를 주입한다(차단 아님).
// CLAUDE.md Key Configuration: 앱 테이블은 core 스키마에 있고 Flyway default-schema는 public이라,
// 스키마 미지정 CREATE는 public에 만들어지거나(신규 오브젝트) public CREATE 권한이 없어 실패한다
// (V33 사고 · 이슈 #223). ADD/DROP COLUMN 등 컬럼 수준 변경은 search_path로 core를 찾아가므로 대상이 아니다.
const fs = require('fs');

let data = {};
try {
  data = JSON.parse(fs.readFileSync(0, 'utf8') || '{}');
} catch (err) {
  process.exit(0);
}

const ti = data.tool_input || {};
const resp = data.tool_response || {};
const filePath = ti.file_path || resp.filePath || '';
if (!filePath) {
  process.exit(0);
}

const norm = filePath.replace(/\\/g, '/');
if (!/\/db\/migration\/V\d[^/]*\.sql$/i.test(norm)) {
  process.exit(0);
}

let content = '';
try {
  content = fs.readFileSync(filePath, 'utf8');
} catch (err) {
  process.exit(0);
}

// 라인 주석은 스캔에서 제외 — 설명 텍스트에 등장하는 DDL 예시가 오탐되는 것을 줄인다.
const sql = content.replace(/--[^\n]*/g, '');

// 캡처 후 값 검사 방식(lookahead 아님). "IF NOT EXISTS" 같은 선택 그룹을 lookahead와 섞으면
// 정규식 백트래킹으로 "IF"가 테이블명으로 잘못 캡처되는 오탐이 생길 수 있어 이 방식을 피한다.
const PATTERNS = [
  { label: 'CREATE TABLE', re: /CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\S+)/gi },
  { label: 'CREATE INDEX ... ON', re: /CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:CONCURRENTLY\s+)?(?:IF\s+NOT\s+EXISTS\s+)?\S+\s+ON\s+(\S+)/gi },
  { label: 'ALTER TABLE ... RENAME TO', re: /ALTER\s+TABLE\s+(\S+)\s+RENAME\s+TO/gi },
  { label: 'ALTER INDEX ... RENAME TO', re: /ALTER\s+INDEX\s+(\S+)\s+RENAME\s+TO/gi },
  { label: 'ALTER TABLE ... ADD CONSTRAINT ... UNIQUE', re: /ALTER\s+TABLE\s+(\S+)\s+ADD\s+CONSTRAINT\s+\S+\s+UNIQUE/gi },
];

const offenders = [];
for (const { label, re } of PATTERNS) {
  let m;
  while ((m = re.exec(sql))) {
    const target = m[1].replace(/[(),;]+$/, '');
    if (!/^core\./i.test(target)) {
      offenders.push(`${label} ${target}`);
    }
  }
}

if (offenders.length > 0) {
  const name = norm.split('/').pop();
  const out = {
    hookSpecificOutput: {
      hookEventName: 'PostToolUse',
      additionalContext:
        `⚠️ ${name} 에 core 스키마 미지정으로 보이는 DDL이 있습니다: ${offenders.join(', ')}. ` +
        '앱 테이블은 core 스키마에 있고 Flyway default-schema는 public이라, 스키마 미지정 CREATE TABLE/INDEX는 ' +
        'public에 만들어져 앱이 못 찾거나(ddl-auto=validate 부팅 실패), RENAME·ADD CONSTRAINT UNIQUE는 public ' +
        'CREATE 권한이 없어 실패합니다(CLAUDE.md Key Configuration 권한 매트릭스, V33 사고 · 이슈 #223). ' +
        'CREATE TABLE core.테이블명 / ON core.테이블명 / ALTER TABLE core.테이블명 형태로 스키마를 명시하세요.',
    },
  };
  process.stdout.write(JSON.stringify(out));
}

process.exit(0);
