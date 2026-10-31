/* Design-only, synthetic fixtures. No production or network behaviour. */
(() => {
  const css = `<style>
  .wf-layout{display:flex;flex-direction:column;gap:10px;height:100%;min-height:0}
  .wf-layout .wf-panel{display:flex;flex-direction:column;min-height:0;overflow:hidden}
  .wf-layout .wf-panel>.panel-body,.wf-layout .wf-panel>.body{flex:1;min-height:0}
  .wf-search{height:34px;display:flex;align-items:center;gap:8px;border:1px solid color-mix(in srgb,currentColor 14%,transparent);border-radius:8px;padding:0 10px;min-width:160px;background:color-mix(in srgb,currentColor 2%,transparent)}
  .wf-layout>.wf-search{flex:0 0 34px;max-height:34px}
  .wf-search input{border:0;outline:0;background:transparent;color:inherit;font:inherit;width:100%;min-width:0}
  .wf-code{height:100%;min-height:0;overflow:auto;padding:3px 0}
  .wf-code pre{margin:0;line-height:1.55;min-height:100%}
  .wf-meta{display:flex;align-items:center;gap:14px;flex-wrap:wrap;font-size:12px;opacity:.72}
  .wf-tree{display:flex;flex-direction:column;gap:4px;padding:9px 0;font-size:13px}
  .wf-tree-row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 12px;border-radius:7px;margin:0 8px}
  .wf-tree-row.child{padding-left:32px;font-size:12px;opacity:.84}
  .wf-tree-row.active{background:rgba(0,122,255,.12);color:#1684ff;box-shadow:inset 3px 0 #1684ff;font-weight:600}
  .wf-tree-count{font-size:11px;opacity:.6;font-variant-numeric:tabular-nums}
  .wf-small-title{font-size:12px;font-weight:600;letter-spacing:.01em}
  .wf-source-note{padding:10px 12px;border-bottom:1px solid color-mix(in srgb,currentColor 10%,transparent);font-size:12px}
  .wf-stage-note{font-size:11px;opacity:.6;white-space:nowrap}
  .wf-side-note{padding:12px;font-size:12px;line-height:1.7;opacity:.72}
  .wf-packet-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;min-height:0;height:100%}
  .wf-route{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}
  .wf-row-details{font-size:11px;opacity:.6;margin-top:4px}
  @media(max-height:850px){.wf-traffic-table{flex-basis:240px!important}}
  </style>`;
  const search = hint => `<label class="wf-search grow"><span aria-hidden="true">⌕</span><input aria-label="${hint}" placeholder="${hint}"></label>`;
  const packet = (title, raw, tools='') => UI.panel(title, `<div class="wf-code">${UI.code(raw)}</div>`, `<div class="row">${UI.tabs(['Pretty','Raw'],0)}${tools}</div>`, 'wf-panel');
  const request = `GET /api/documents/101 HTTP/1.1\nHost: example.test\nAccept: application/json\nCookie: SID=ALICE_SYNTHETIC\nReferer: https://example.test/projects\nX-Request-ID: req-0101\n\n`;
  const response = `HTTP/1.1 200 OK\nContent-Type: application/json; charset=utf-8\nCache-Control: no-cache\nContent-Length: 248\n\n{\n  "id": 101,\n  "ownerId": "alice",\n  "title": "프로젝트 회의록",\n  "projectId": 12,\n  "permission": "owner",\n  "updatedAt": "2026-10-31T09:42:18Z",\n  "content": "합성 데이터로 작성한 문서입니다."\n}`;
  const methods = ['전체', 'GET', 'POST', '오류'];
  Object.assign(window.DESIGNS, {
    traffic: {
      title:'요청·응답 목록', nav:'traffic', group:'트래픽',
      goal:'필요한 요청을 찾고 원문을 읽은 뒤 검증 도구로 연결',
      primary:'Repeater 준비',
      rationale:[
        '찾기 → 원문 확인 → 요청 활용 순서로 묶고, 선택 URL·응답 상태와 도구 연결을 한 문맥줄로 합쳤습니다.',
        'Repeater를 한 가지 주 동작으로 두고 계정 비교·입력값 검사·증거 연결을 요청 활용 메뉴에 통합했습니다.',
        '패킷 검색은 필요할 때 펼쳐 원문 높이를 확보하고 대상·수집 설정은 한 문맥줄로 압축했습니다.'
      ],
      render() {
        const rows = [
          ['1','GET','/api/users/me',UI.chip('200','success'),'Alice','10:04:12','현재 로그인 사용자'],
          ['2','GET','/api/documents/101',UI.chip('200','success'),'Alice','10:05:42','문서 상세 조회'],
          ['3','GET','/api/documents/101',UI.chip('403','warning'),'Bob','10:05:47','다른 계정의 동일 요청'],
          ['4','GET','/api/documents/102',UI.chip('404','muted'),'Alice','10:06:03','존재하지 않는 문서'],
          ['5','GET','/api/projects',UI.chip('200','success'),'Alice','10:06:18','프로젝트 목록'],
          ['6','POST','/api/projects/12/notes',UI.chip('201','success'),'Alice','10:06:31','회의록 작성'],
          ['7','GET','/assets/app.js',UI.chip('200','success'),'—','10:06:45','프런트엔드 소스'],
          ['8','GET','/api/admin/users',UI.chip('403','warning'),'Alice','10:07:05','관리 기능 접근 확인'],
          ['9','POST','/api/auth/login',UI.chip('200','success'),'—','10:07:21','로그인 요청'],
          ['10','GET','/api/documents?projectId=12',UI.chip('200','success'),'Alice','10:07:38','문서 목록 필터']
        ];
        const context = UI.toolbar(
          `<div class="row">${UI.field('대상','example.test')}${UI.field('계정','전체')}${UI.chip('수집 중 · SCOPE','success')}</div>`,
          UI.menu('수집 설정',['SCOPE 규칙','수집 방식','검색·목록 설정','목록 고정'])
        );
        const filter = UI.toolbar(search('URL · 메서드 · 상태로 검색'),UI.tabs(methods,0));
        const selection = UI.toolbar(
          `<div class="row"><strong class="wf-route">GET /api/documents/101</strong>${UI.chip('200 OK','success')}<span class="muted small">Alice · 10:05:42</span></div>`,
          `<div class="row">${UI.menu('요청 활용',['계정 비교 준비','입력값 검사 준비','응답에서 API 찾기','증거에 추가'])}${UI.btn('Repeater 준비',{primary:true})}</div>`
        );
        return css+`<div class="wf-layout">${context}${filter}
          <div class="wf-traffic-table" style="flex:0 0 310px;min-height:0;overflow:hidden;display:flex;flex-direction:column">${UI.table(['번호','메서드','URL','상태','계정','시간','메모'],rows,{selected:1})}</div>
          ${selection}
          <div class="wf-packet-grid" style="flex:1">
          ${packet('요청',request,UI.menu('원문 도구',['Hex','Render','패킷에서 검색 · Ctrl+F','요청 복사']))}
          ${packet('응답',response,UI.menu('원문 도구',['Hex','Render','패킷에서 검색 · Ctrl+F','응답 복사']))}
          </div><div class="wf-meta"><span>전체 120건</span><span>표시 10건</span><span>선택 1건</span><span class="grow"></span><span>요청 준비는 서버에 전송하지 않습니다</span></div>
        </div>`;
      }
    },
    mapper: {
      title:'기능 자동 매핑', nav:'mapper', group:'트래픽',
      goal:'요청을 기능으로 분류하고 원문과 분류 근거를 확인',
      primary:'분류 수정',
      rationale:[
        '프로젝트 관리·기능 편집·요청 분류를 서로 다른 문맥에 배치해 분류 작업의 우선순위를 분명히 했습니다.',
        '기능 트리의 편집과 업무 연결을 한 메뉴로 묶고, 선택 요청은 분류 수정 하나를 강조합니다.',
        '미분류 개수는 직접 접근하는 필터로 노출하고 반복되는 실행 정보보다 URL·세션·분류 확실성을 앞에 둡니다.'
      ],
      render() {
        const tree = `<div class="wf-tree">
          <div class="wf-tree-row"><span>⌄ 로그인</span><span class="wf-tree-count">4</span></div>
          <div class="wf-tree-row child"><span>로그인 요청</span><span class="wf-tree-count">2</span></div>
          <div class="wf-tree-row child"><span>세션 확인</span><span class="wf-tree-count">2</span></div>
          <div class="wf-tree-row"><span>› 사용자</span><span class="wf-tree-count">6</span></div>
          <div class="wf-tree-row active"><span>⌄ 문서</span><span class="wf-tree-count">8</span></div>
          <div class="wf-tree-row child"><span>목록 조회</span><span class="wf-tree-count">2</span></div>
          <div class="wf-tree-row child"><span>상세 조회</span><span class="wf-tree-count">4</span></div>
          <div class="wf-tree-row child"><span>문서 수정</span><span class="wf-tree-count">2</span></div>
          <div class="wf-tree-row"><span>› 프로젝트</span><span class="wf-tree-count">5</span></div>
          <div class="wf-tree-row"><span>› 관리자</span><span class="wf-tree-count">3</span></div>
          <div class="divider"></div>
          <div class="wf-tree-row"><span>미분류 요청</span>${UI.chip('7','warning')}</div>
        </div>`;
        const rows = [
          ['GET','/api/documents',UI.chip('200','success'),'Alice',UI.chip('높음','success')],
          ['GET','/api/documents/101',UI.chip('200','success'),'Alice',UI.chip('높음','success')],
          ['GET','/api/documents/101',UI.chip('403','warning'),'Bob',UI.chip('높음','success')],
          ['GET','/api/documents/101',UI.chip('401','muted'),'비로그인',UI.chip('높음','success')],
          ['GET','/api/documents/102',UI.chip('404','muted'),'Alice',UI.chip('높음','success')],
          ['PATCH','/api/documents/101',UI.chip('200','success'),'Alice',UI.chip('중간','warning')],
          ['PATCH','/api/documents/101',UI.chip('403','warning'),'Bob',UI.chip('중간','warning')],
          ['GET','/api/documents?projectId=12',UI.chip('200','success'),'Alice',UI.chip('높음','success')]
        ];
        const context=UI.toolbar(`<div class="row">${UI.menu('프로젝트 · example.test',['프로젝트 열기','프로젝트 저장'])}${UI.chip('수집 중 · SCOPE','success')}</div>`,UI.btn('매핑 설정'));
        const filters=UI.toolbar(search('URL · 메서드 · 매핑으로 검색'),`<div class="row">${UI.field('실행','전체')}${UI.field('세션','전체')}</div>`);
        const tableHeader=UI.toolbar(`<div class="row"><strong>문서</strong><span class="muted small">요청 8개 · 선택 1개</span></div>`,`<div class="row">${UI.menu('선택 요청',['Repeater 준비','원본 요청 보기','요청 복사'])}${UI.btn('분류 수정',{primary:true})}</div>`);
        const packets=`<div class="wf-packet-grid">${packet('요청',request,UI.menu('원문 도구',['Hex','요청 복사']))}${packet('응답',response,UI.menu('원문 도구',['Hex','응답 복사']))}</div>`;
        return css+`<div class="wf-layout">${context}<div style="display:grid;grid-template-columns:218px minmax(0,1fr);gap:12px;flex:1;min-height:0">
          ${UI.panel('기능 · 12개',tree,UI.menu('기능 편집',['이름·상태·메모','업무 연결']),'wf-panel')}
          <div class="wf-layout">${filters}${tableHeader}<div style="flex:0 0 240px;min-height:0;overflow:hidden;display:flex;flex-direction:column">${UI.table(['메서드','URL','상태','세션','매핑'],rows,{selected:1})}</div>
          ${UI.tabs(['요청·응답','분류 근거'],0)}<div style="flex:1;min-height:0">${packets}</div></div>
        </div></div>`;
      }
    },
    js: {
      title:'JS에서 API 찾기', nav:'js', group:'탐색·정보 수집',
      goal:'발견 경로와 호출 코드를 함께 확인한 뒤 검증 요청 준비',
      primary:'Repeater 준비',
      rationale:[
        '경로 목록을 주 작업으로 올리고, 경로와 호출 소스를 나란히 배치해 세 방향으로 흩어진 확인 동선을 줄였습니다.',
        '표와 중복되는 URL·메서드 사실표를 줄이고 발견 입력과 분석 근거는 필요한 경우 펼치도록 구성했습니다.',
        '분석 유형과 분석 관리 작업을 구분해 묶고 선택 경로의 다음 동작인 Repeater 준비만 강조합니다.'
      ],
      render() {
        const rows = [
          ['GET','/api/users/me','구문 확인','app.js:92'],
          ['GET','/api/documents/{id}','구문 확인','app.js:184'],
          ['GET','/api/documents','구문 확인','app.js:213'],
          ['PATCH','/api/documents/{id}','구문 확인','app.js:245'],
          ['GET','/api/projects','구문 확인','app.js:278'],
          ['POST','/api/projects','구문 확인','app.js:310'],
          ['GET','/api/projects/{id}/notes','구문 확인','app.js:339'],
          ['POST','/api/auth/login','구문 확인','auth.js:44'],
          ['POST','/api/auth/logout','구문 확인','auth.js:79'],
          ['GET','/api/admin/users','구문 확인','admin.js:31'],
          ['—','/api/search','메서드 미확정','search.js:52'],
          ['GET','/api/notifications','구문 확인','app.js:402']
        ];
        const source=`// app.js · 문서 상세 조회\n// 선택한 경로의 호출 위치: 184\n\nconst API_BASE = "/api";\n\nexport async function getDocument(documentId) {\n  const endpoint =\n    API_BASE + "/documents/" + documentId;\n\n  const response = await fetch(endpoint, {\n    method: "GET",\n    credentials: "include",\n    headers: {\n      "Accept": "application/json"\n    }\n  });\n\n  if (!response.ok) {\n    throw new Error("Document request failed");\n  }\n\n  return response.json();\n}\n\nexport async function updateDocument(id, data) {\n  return fetch(API_BASE + "/documents/" + id, {\n    method: "PATCH",\n    body: JSON.stringify(data)\n  });\n}`;
        const context=UI.toolbar(`<div class="row">${UI.field('소스','assets/app.js',{options:['assets/app.js','assets/auth.js','assets/admin.js','전체 분석 이력']})}${UI.chip('분석 ON','success')}</div>`,UI.menu('분석 관리',['대상 설정','트래픽 대조','버전 비교','분석 목록','분석 상세','가림 결과 복사','분석 처리·재시도','분석 초기화']));
        const kind=UI.toolbar(UI.tabs(['경로 12','민감정보 2','도메인 3'],0),UI.menu('분석 유형',['클라우드','NPM 의존성','소스맵','복원 원본']));
        const left=`<div class="wf-layout">${kind}${search('경로 · 메서드 · 입력 이름 검색')}<div style="flex:1;min-height:0;overflow:auto">${UI.table(['메서드','발견 경로','확인 상태','호출 위치'],rows,{selected:1})}</div><div class="wf-meta"><span>경로 12개</span><span>메서드 미확정 1개</span><span>원문에서 추출한 후보입니다</span></div></div>`;
        const selectedHead=UI.toolbar(`<div class="row"><strong class="wf-route">GET /api/documents/{id}</strong></div>`,`<div class="row">${UI.menu('선택 경로',['원문 위치로 이동','관찰된 API 패킷 보기','URL 목록 복사'])}${UI.btn('Repeater 준비',{primary:true})}</div>`);
        const codeBody=`<div class="wf-layout" style="gap:0"><div class="wf-source-note"><strong>app.js:184</strong><span style="margin-left:10px;opacity:.6">호출 위치 확인</span></div><div class="wf-code" style="flex:1">${UI.code(source,{highlight:10})}</div></div>`;
        const detail=UI.toolbar(`<span class="small">경로 변수 <code>documentId</code> · 요청 미전송</span>`,UI.menu('분석 근거',['발견 입력 이름','URL 추론 근거','원문 감지 위치 탐색']));
        return css+`<div class="wf-layout">${context}<div style="display:grid;grid-template-columns:minmax(0,1.04fr) minmax(0,1fr);gap:14px;flex:1;min-height:0">${left}<div class="wf-layout">${selectedHead}${UI.panel('호출 소스',codeBody,'','wf-panel grow')}${detail}</div></div></div>`;
      }
    },
    'response.recon': {
      title:'응답 보안 분석', nav:'response.recon', group:'보안 분석·검증',
      goal:'관찰 내용을 응답 원문과 대조하고 검토한 항목을 증거로 정리',
      primary:'증거로 정리',
      rationale:[
        '관찰 목록과 검사 영역을 나란히 두고 원문을 크게 배분해 목록→근거→원문으로 반복 이동하던 동선을 줄였습니다.',
        '분류·위치·URL이 반복되는 사실표를 한 요약줄로 합치고 원문·관련 요청·영향 URL을 같은 영역에서 전환합니다.',
        '증거로 정리를 주 동작으로 두고 복사·원본 연결은 관찰 작업에, 초기화·재시도는 분석 관리에 구분했습니다.'
      ],
      render() {
        const rows = [
          ['오류 구현 정보 노출 후보','/api/users/me','2'],
          ['오류 구현 정보 노출 후보','/api/documents/102','1'],
          ['HTML 응답 헤더 검토','/projects','2'],
          ['HTTPS HSTS 검토','/projects','1'],
          ['세션 쿠키 속성 검토','/api/auth/login','2'],
          ['설정 키 노출 후보','/config/application','1'],
          ['HTML 응답 헤더 검토','/documents','1'],
          ['응답 분석 범위 제한','/assets/vendor.js','1']
        ];
        const raw=`HTTP/1.1 200 OK\nContent-Type: application/json; charset=utf-8\nCache-Control: no-store\nX-Request-ID: req-0042\n\n{\n  "id": "alice",\n  "name": "Alice",\n  "role": "member",\n  "diagnostic": {\n    "type": "java.lang.Exception",\n    "message": "Synthetic diagnostic example",\n    "requestId": "req-0042"\n  }\n}`;
        const top=UI.toolbar(UI.tabs(['응답 관찰 8','API 문서 6','저장·조회 연결 2'],0),UI.menu('분석 관리',['분석 처리·재시도','관찰 초기화']));
        const left=`<div class="wf-layout">${search('분류 · URL · 관찰 내용 검색')}<div class="row small muted" style="justify-content:space-between;padding:0 4px"><span>관찰 8개 · 원본 응답 연결</span><span>example.test</span></div><div style="flex:1;overflow:auto;min-height:0">${UI.table(['관찰','영향 경로','횟수'],rows,{selected:0})}</div><div class="wf-side-note">관찰된 문자열·헤더를 먼저 확인합니다.<br>검토한 항목만 원본과 함께 증거로 연결합니다.</div></div>`;
        const title=UI.toolbar(`<div class="col" style="gap:5px"><strong>오류 구현 정보 노출 후보</strong><div class="wf-meta"><span>GET /api/users/me</span><span>Body: 42</span>${UI.chip('관찰 후보','warning')}</div></div>`,`<div class="row">${UI.menu('관찰 작업',['근거 복사','원본 요청 보기','영향 URL 복사'])}${UI.btn('증거로 정리',{primary:true})}</div>`);
        const reader=`<div class="wf-layout" style="gap:0">${UI.tabs(['응답 원문','관련 요청','영향 URL','분석 근거'],0)}<div class="wf-code" style="flex:1">${UI.code(raw)}</div></div>`;
        const right=`<div class="wf-layout">${title}${UI.note('java.lang.Exception 문자열이 응답에서 관찰되었습니다. 실제 영향과 재현 여부는 직접 확인하세요.','info')}${UI.panel('원문 대조',reader,UI.menu('원문 보기',['Pretty','Raw','Hex','패킷에서 검색 · Ctrl+F','응답 복사']),'wf-panel grow')}</div>`;
        return css+`<div class="wf-layout">${top}<div style="display:grid;grid-template-columns:minmax(0,.8fr) minmax(0,1.3fr);gap:14px;flex:1;min-height:0">${left}${right}</div></div>`;
      }
    }
  });
})();
