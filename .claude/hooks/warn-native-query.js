// PostToolUse(Edit|Write) soft 경고: repository/*.java 에 nativeQuery=true 가 있는데
// '문서화된 예외' 주석이 없으면 모델에 리마인더를 주입한다(차단 아님).
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
if (!/\/repository\/[^/]*\.java$/.test(norm)) {
  process.exit(0);
}

let content = '';
try {
  content = fs.readFileSync(filePath, 'utf8');
} catch (err) {
  process.exit(0);
}

if (/nativeQuery\s*=\s*true/.test(content) && !content.includes('문서화된 예외')) {
  const name = norm.split('/').pop();
  const out = {
    hookSpecificOutput: {
      hookEventName: 'PostToolUse',
      additionalContext:
        `⚠️ ${name} 에 nativeQuery=true 가 있는데 '문서화된 예외' 주석이 없습니다. ` +
        '하네스 규칙: 동적 조회는 QueryDSL, 단순 조회·카운트는 Spring Data 파생 쿼리·JPQL. ' +
        "미매핑 테이블 조인 등 불가피한 경우에만 사유 주석('문서화된 예외')을 달아 native 를 허용하세요."
    }
  };
  process.stdout.write(JSON.stringify(out));
}

process.exit(0);
