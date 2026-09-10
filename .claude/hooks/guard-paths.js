// PreToolUse(Edit|Write) 가드:
//  (a) build/ 생성물(QueryDSL Q클래스·컴파일 결과) 편집 차단
//  (b) 이미 존재하는 Flyway 마이그레이션(db/migration/V*.sql) 수정 차단 — 신규 Vn Write는 허용
//  (c) 신규 마이그레이션의 Vn 번호가 로컬에 이미 존재하는 다른 파일과 충돌하면 차단
//      (Flyway는 버전 중복을 허용하지 않음 — CLAUDE.md Key Configuration의 "타 PR과의 번호 충돌"과는
//      별개로, 같은 워크트리 안에서의 명백한 실수를 잡는다. 열려 있는 다른 PR과의 충돌은 로컬에서
//      볼 수 없으므로 gh pr list 등으로 별도 확인이 필요하다.)
// 차단은 exit code 2 + stderr. 판단 불가 시 fail-open(exit 0).
const fs = require('fs');
const path = require('path');

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

  if (tool === 'Write' && !exists) {
    const versionMatch = norm.match(/\/V(\d+(?:_\d+)*)__[^/]*\.sql$/i);
    if (versionMatch) {
      const newVersion = versionMatch[1];
      const newName = path.basename(norm);
      const dir = path.dirname(filePath);
      let siblings = [];
      try {
        siblings = fs.readdirSync(dir);
      } catch (err) {
        siblings = [];
      }
      const conflict = siblings.find((sibling) => {
        const m = sibling.match(/^V(\d+(?:_\d+)*)__.*\.sql$/i);
        return m && m[1] === newVersion && sibling.toLowerCase() !== newName.toLowerCase();
      });
      if (conflict) {
        deny(`⛔ 마이그레이션 버전 V${newVersion} 이 이미 ${conflict} 로 존재합니다 (Flyway는 버전 중복을 허용하지 않습니다). `
          + '다음 번호로 재지정하세요. 그리고 이건 로컬 파일끼리의 충돌만 잡은 것입니다 — CLAUDE.md Key '
          + 'Configuration 규칙대로, develop HEAD뿐 아니라 그 시점에 열려 있는 다른 PR의 마이그레이션 번호와도 '
          + '겹칠 수 있으니 gh pr list 등으로 확인하세요.');
      }
    }
  }
}

process.exit(0);
