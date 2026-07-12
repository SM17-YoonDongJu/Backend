// PreToolUse(Edit|Write) 가드:
//  (a) build/ 생성물(QueryDSL Q클래스·컴파일 결과) 편집 차단
//  (b) 이미 존재하는 Flyway 마이그레이션(db/migration/V*.sql) 수정 차단 — 신규 Vn Write는 허용
// 차단은 exit code 2 + stderr. 판단 불가 시 fail-open(exit 0).
const fs = require('fs');

function deny(message) {
  process.stderr.write(message + '\n');
  process.exit(2);
}

let data = {};
try {
  data = JSON.parse(fs.readFileSync(0, 'utf8') || '{}');
} catch (err) {
  process.exit(0);
}

const tool = data.tool_name || '';
const filePath = (data.tool_input && data.tool_input.file_path) || '';
if (!filePath) {
  process.exit(0);
}

const norm = filePath.replace(/\\/g, '/');

if (/(^|\/)build\//.test(norm)) {
  deny('⛔ build/ 산출물(생성 소스·컴파일 결과)은 편집 금지. QueryDSL Q클래스 등은 gradle이 생성합니다. 원본 소스를 수정하세요.');
}

if (/\/db\/migration\/V\d[^/]*\.sql$/i.test(norm)) {
  let exists = false;
  try {
    exists = fs.existsSync(filePath);
  } catch (err) {
    exists = false;
  }
  if (tool === 'Edit' || (tool === 'Write' && exists)) {
    const name = norm.split('/').pop();
    deny(`⛔ 이미 존재하는 Flyway 마이그레이션(${name}) 수정 금지 — 체크섬이 깨져 ddl-auto=validate 부팅이 실패합니다. 새 V{n}__설명.sql 을 추가하세요.`);
  }
}

process.exit(0);
