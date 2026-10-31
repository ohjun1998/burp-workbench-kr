/* Design prototype only. Synthetic fixtures; no target requests or provider queries. */
(() => {
  const esc = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const select = (values, label='선택') => `<select aria-label="${esc(label)}" style="height:31px;border:1px solid var(--line);border-radius:7px;background:var(--surface);color:var(--ink);padding:0 9px;max-width:100%">${values.map(v=>`<option>${esc(v)}</option>`).join('')}</select>`;
  const search = hint => `<label class="search"><span aria-hidden="true">⌕</span><input aria-label="${esc(hint)}" placeholder="${esc(hint)}"><kbd>⌘ F</kbd></label>`;
  const stopped = label => `<button class="btn" disabled aria-label="${esc(label)}">■ ${esc(label)}</button>`;
  const role = (letter, name, roleName, tone='blue') => `<button class="role-chip" data-action="accounts" style="text-align:left;flex:1;cursor:pointer"><span class="avatar" style="${tone==='green'?'background:var(--green-soft);color:var(--green)':tone==='gray'?'background:var(--soft);color:var(--muted)':''}">${letter}</span><span><b>${name}</b><span class="muted" style="margin-left:7px">${roleName}</span></span><span style="margin-left:auto">${UI.chip('연결됨',tone==='gray'?'':'blue')}</span></button>`;
  const request = `GET /api/documents/101 HTTP/1.1\nHost: example.test\nCookie: SID=ALICE_EXAMPLE\nAccept: application/json\n\n`;
  const payloadRequest = `GET /search?q=§PAYLOAD§ HTTP/1.1\nHost: example.test\nCookie: SID=ALICE_EXAMPLE\nAccept: text/html\n\n`;
  const domainContext = () => UI.toolbar(`<div class="row">${UI.field('대상','example.test')}<label class="small row"><input type="checkbox" checked> 하위 도메인 포함</label>${UI.chip('SCOPE 포함','green')}</div>`,`<span class="small muted">현재 프로젝트</span>`);
  const inspectionCondition = () => `<div class="row small" style="gap:12px;flex-wrap:wrap">${UI.chip('GET','blue')}<span>example.test</span><span class="muted">Alice 인증 사용</span><span class="muted">XSS · URL 인코딩</span>${UI.chip('SCOPE 안','green')}</div>`;

  const cveView = which => which===0
    ? `<div style="margin:0 -14px">${UI.facts([['영향 범위','≥ 1.18.0 · < 1.24.0'],['원격·인증','원격 · 인증 불필요 조건'],['모듈·운영체제','추가 확인 필요'],['배포판·백포트','공급사 공지와 패치 확인 필요'],['수정 버전','공급사 공지에서 확인']])}</div><div class="note amber box">조건 일치는 실제 취약점 확인이 아닙니다. 모듈·설정·패치 조건을 함께 확인하세요.</div>`
    : `<div style="margin:0 -14px">${UI.facts([['관측 헤더','Server: nginx/1.22.1'],['관측 원본','GET /api/documents/101'],['원본 ID','example-original-101'],['NVD','조회 완료 · 설계용 예시'],['KEV','최신 조회 완료 · 설계용 예시'],['EPSS','23.40% · 설계용 합성 수치']])}</div>${UI.note('조회 시각과 공급사 원문을 함께 대조하세요.')}`;
  document.addEventListener('click', event => {
    const close=event.target.closest('[data-action="account-close"]');
    if(close){event.stopImmediatePropagation();if(window.DESIGN_NAVIGATE)window.DESIGN_NAVIGATE('auth');return;}
    const tab=event.target.closest('[data-exec-tabs="cve"] [data-tab]');
    if(tab){
      event.stopImmediatePropagation();
      tab.parentElement.querySelectorAll('[data-tab]').forEach(button=>{button.classList.toggle('active',button===tab);button.setAttribute('aria-selected',String(button===tab));});
      const content=tab.closest('.panel').querySelector('[data-exec-content="cve"]');
      if(content)content.innerHTML=cveView(Number(tab.dataset.tab));
    }
  },true);

  Object.assign(window.DESIGNS ||= {}, {
    auth: {
      title:'계정 권한 비교', nav:'auth', group:'보안 분석·검증', goal:'역할별 응답 차이에서 직접 검토할 요청을 찾기', primary:'비교 준비',
      rationale:[
        '큰 계정 카드를 역할·신원·연결 상태가 보이는 한 줄 요약으로 통합했습니다.',
        '주 동작을 ‘비교 준비’로 명확히 하고, 계정·세션·정책 보완은 하나의 관리 메뉴에 모았습니다.',
        '기록 표와 응답 비교를 연결하고 반복 설명을 줄여 패킷 읽기에 공간을 배분했습니다.'
      ],
      render(){
        const rows=[
          ['GET','/api/users/me',UI.chip('200','green'),UI.chip('200','green'),UI.chip('401','amber'),'신원 대조'],
          ['GET','/api/documents/101',UI.chip('200','green'),UI.chip('403','amber'),UI.chip('403','amber'),UI.chip('확인 필요','amber')],
          ['GET','/api/projects/12',UI.chip('200','green'),UI.chip('200','green'),UI.chip('403','amber'),'권한 정책 대조'],
          ['POST','/api/documents/101',UI.chip('200','green'),'미실행','미실행',UI.chip('쓰기 조건 확인','amber')]
        ];
        const recordActions=`<div class="row" style="width:630px;max-width:100%">${search('URL·결과 검색')}${select(['전체 기록','차이 있는 요청','미확인'],'비교 기록 필터')}${UI.btn('비교 준비',{primary:true,action:'auth-prepare'})}${stopped('중지')}</div>`;
        const readers=`<div class="grid2 grow">${UI.panel('Alice · 문서 소유자',UI.code('HTTP/1.1 200 OK\nContent-Type: application/json\n\n{\n  "id": 101,\n  "ownerId": "alice",\n  "title": "Project notes"\n}'),UI.chip('계정 1 · 200','blue'),'grow')}${UI.panel('Bob · 다른 사용자',UI.code('HTTP/1.1 403 Forbidden\nContent-Type: application/json\n\n{\n  "error": "접근 권한 없음",\n  "requestId": "example-101"\n}'),UI.chip('계정 2 · 403','amber'),'grow')}</div>`;
        return `${UI.toolbar(`<div class="row">${UI.field('대상','https://example.test')}${UI.chip('인증 후보 수집 중','green')}</div>`,UI.menu('계정·정책',['계정·세션 관리','인증 후보 연결','판정 정책','비교 기록 관리']))}
          <div class="row" style="gap:10px">${role('A','Alice','문서 소유자')}${role('B','Bob','다른 사용자','green')}${role('—','비로그인','인증 정보 없음','gray')}</div>
          ${UI.panel('비교 기록 · 4건',UI.table(['메서드','요청 경로','Alice','Bob','비로그인','검토 상태'],rows,{selected:1}),recordActions)}
          ${UI.panel('GET /api/documents/101',`<div class="section" style="padding:8px 12px">${UI.toolbar(UI.tabs(['응답 비교','계정 간 차이','선택 계정 요청','판정 근거'],0),`<div class="row small muted">비교 계정 ${select(['Bob · 다른 사용자','비로그인'],'상세 비교 계정')}</div>`)}</div>${readers}<div class="section" style="padding:7px 13px">${UI.note('Alice 200 / Bob 403 / 비로그인 403 · 응답 차이를 확인한 뒤 실제 권한 정책과 대조하세요.','amber')}</div>`,UI.chip('검토 필요','amber'),'grow')}`;
      }
    },
    'auth.accounts': {
      title:'계정 권한 비교 · 인증 후보 연결',nav:'auth',group:'보안 분석·검증',goal:'같은 대상의 인증 후보를 비교 역할에 연결하기',primary:'닫기',
      rationale:[
        '대상 Origin을 한 번만 보여 주고 역할별 선택과 연결 상태를 같은 행에 정렬했습니다.',
        '미사용 추가 계정은 접어서 기본 비교 역할을 먼저 보이게 했습니다.',
        '현재 연결이 즉시 반영되는 동작을 유지하므로 별도의 적용 단계를 만들지 않았습니다.'
      ],
      render(){
        const accountRow=(title,sub,values,state) => `<div style="display:grid;grid-template-columns:155px minmax(0,1fr) 75px;gap:14px;align-items:center;padding:14px 0;border-bottom:1px solid var(--line)"><div><b style="font-size:12px">${title}</b><div class="muted small" style="margin-top:4px">${sub}</div></div>${select(values,title)}<span>${UI.chip(state,'blue')}</span></div>`;
        const form=`<div class="section" style="padding:20px 24px">${UI.note('연결 대상  https://example.test · 같은 대상의 인증 후보만 표시합니다.')}
          ${accountRow('계정 1','문서 소유자 역할',['Alice · alice@example.test','Bob · bob@example.test','미지정'],'연결됨')}
          ${accountRow('계정 2','다른 사용자 역할',['Bob · bob@example.test','Alice · alice@example.test','미지정'],'연결됨')}
          <div style="display:grid;grid-template-columns:155px minmax(0,1fr) 75px;gap:14px;align-items:center;padding:15px 0"><div><b style="font-size:12px">비로그인</b><div class="muted small" style="margin-top:4px">인증 없이 응답 비교</div></div><div class="small muted">Cookie·Authorization을 사용하지 않습니다.</div>${UI.chip('사용','')}</div>
          <details class="disclosure" style="margin-top:8px"><summary>추가 비교 계정 · 현재 미지정</summary><div>${accountRow('계정 3','필요할 때만 연결',['미지정','Carol · carol@example.test'],'선택 가능')}</div></details>
          ${UI.note('계정 선택은 즉시 연결됩니다. 연결·설정 변경만으로 요청을 보내지 않습니다.')}
          <div class="divider" style="margin:12px 0"></div>${UI.toolbar(`<span class="small muted">연결 역할은 점검 대상의 권한 정책과 직접 대조하세요.</span>`,`${UI.btn('인증 후보 관리',{action:'account-candidates'})}${UI.btn('닫기',{primary:true,action:'account-close'})}`)}</div>`;
        return `<div class="grow col" style="justify-content:center;align-items:center"><div class="col" style="width:900px;max-width:100%">${UI.panel('인증 후보 연결',form,UI.chip('비교 역할 3개','blue'))}</div></div>`;
      }
    },
    payload: {
      title:'입력값 보안 검사 · 요청 준비',nav:'payload',group:'보안 분석·검증',goal:'검사 위치와 전송 조건을 확인하고 직접 검사 시작하기',primary:'검사 시작',
      rationale:[
        '요청·검사 후보의 중첩 탭을 없애고 요청 편집 옆에서 핵심 조건을 함께 확인하도록 구성했습니다.',
        '시작 전 확인할 대상·인증·위치·요청 한도를 짧은 요약으로 드러냈습니다.',
        '초기화·미리보기·세부 옵션은 필요할 때 펼쳐 편집기와 주 실행을 우선했습니다.'
      ],
      render(){
        const condition=`<div class="section stack" style="gap:12px">${UI.field('검사 유형','XSS',{options:['XSS','SQL Injection','SSRF','경로 탐색 / LFI','명령어 삽입','사용자 정의']})}${UI.field('인코딩','URL 인코딩',{options:['URL 인코딩','RAW','JSON']})}${UI.field('총 요청 한도','8회')}${UI.field('요청 간격','500 ms')}<div class="divider"></div><div class="small"><b>검사 위치</b><div style="margin-top:7px">${UI.chip('Query · q','blue')} <span class="muted">1곳 지정됨</span></div></div>${UI.note('기준·대조 요청도 총 요청 한도에 포함됩니다.')}
          <details class="disclosure"><summary>검사 후보 · 4개</summary><div><textarea class="editor" aria-label="검사 후보" style="height:145px;border:1px solid var(--line);border-radius:6px">WB_CANARY_101\n&lt;b&gt;WB_CANARY_101&lt;/b&gt;\nWB_CONTROL_101\nWB_REPEAT_101</textarea>${UI.menu('후보 관리',['후보 초기화','전체 후보 보기'])}</div></details>
          <details class="disclosure"><summary>고급 실행 조건</summary><div class="stack">${UI.field('응답 제한 시간','10초')}${select(['적응형 후보 선택','전체 목록 순서대로'],'실행 방식')}<label class="small row"><input type="checkbox" checked> 현재 요청의 인증 사용</label></div></details>
          </div>`;
        return `${UI.toolbar(UI.tabs(['요청 준비','실행·결과'],0),`${UI.btn('검사 시작',{primary:true,icon:'play',action:'results'})}${stopped('중지')}`)}
          <div class="row" style="min-height:30px">${inspectionCondition()}<span class="small muted" style="margin-left:auto">위치 1곳 · 최대 8회 전송</span></div>
          <div class="split grow" style="grid-template-columns:minmax(0,1fr) 285px">${UI.panel('검사 요청',UI.code(payloadRequest,{highlight:1}),`${UI.btn('선택 값 표시',{action:'payload-mark'})}${UI.menu('편집',['정렬 미리보기','기준값 확인'])}`,'grow')}${UI.panel('검사 조건',condition,'','grow')}</div>
          ${UI.note('준비됨 · q 위치에 검사값을 적용합니다. 시작하면 기준·대조를 포함해 실제 요청을 전송합니다.','green')}`;
      }
    },
    'payload.results': {
      title:'입력값 보안 검사 · 실행·결과',nav:'payload',group:'보안 분석·검증',goal:'관측한 결과와 실제 요청·응답을 대조해 재현 근거 만들기',primary:'결과를 증거로',
      rationale:[
        '결과 활용을 주 동작으로 바꾸고 재검사·CSV는 보조 메뉴로 구분했습니다.',
        '큰 판정 사유 박스를 짧은 선택 결과 요약으로 합쳐 요청·응답을 더 넓게 읽게 했습니다.',
        '반사 관찰과 브라우저 실행 확인을 구분해 결과를 과장하지 않도록 표시했습니다.'
      ],
      render(){
        const rows=[['1','baseline',UI.chip('200','green'),'112','84 ms','기준 응답'],['2','WB_CANARY_101',UI.chip('200','green'),'117','91 ms',UI.chip('반사 관찰','amber')],['3',esc('<b>WB_CANARY_101</b>'),UI.chip('200','green'),'136','89 ms',UI.chip('반사 관찰','amber')],['4','WB_CONTROL_101',UI.chip('200','green'),'118','82 ms','대조 응답']];
        const requestCode='GET /search?q=%3Cb%3EWB_CANARY_101%3C%2Fb%3E HTTP/1.1\nHost: example.test\nCookie: SID=ALICE_EXAMPLE\nAccept: text/html\n\n';
        const responseCode='HTTP/1.1 200 OK\nContent-Type: text/html; charset=UTF-8\nContent-Length: 136\n\n<html>\n  <body>\n    <b>&lt;b&gt;WB_CANARY_101&lt;/b&gt;</b>\n  </body>\n</html>';
        return `${UI.toolbar(UI.tabs(['요청 준비','실행·결과'],1),`${UI.btn('결과를 증거로',{primary:true,action:'evidence'})}${UI.btn('Repeater 열기',{action:'payload-repeater'})}${UI.menu('검사 관리',['다시 검사','CSV 저장'])}${stopped('중지')}`)}
          ${UI.toolbar(`<div class="row">${UI.chip('검사 완료','green')}<span class="small">실제 요청 4 / 최대 8회</span><span class="muted small">XSS · Alice 인증 사용</span></div>`,`<span class="muted small">중단·한도·오류 내역은 상세 근거에서 확인</span>`)}
          ${UI.panel('검사 결과 · 4건',UI.table(['번호','검사값','HTTP','크기','응답 시간','관측 결과'],rows,{selected:2}),UI.tabs(['전체 4','관찰 2','기준·대조 2'],0))}
          <div class="note amber box"><b>선택 결과 · 반사 관찰</b><span>응답에 입력 문자열이 포함됐습니다. 브라우저 실행은 확인하지 않았습니다.</span></div>
          <div class="grid2 grow">${UI.panel('실제 요청',UI.code(requestCode),UI.menu('요청 보기',['Pretty','Raw','요청 복사']),'grow')}${UI.panel('실제 응답',UI.code(responseCode),UI.menu('응답 보기',['Pretty','Raw','응답 복사']),'grow')}</div>
          <details class="disclosure"><summary>선택 결과의 판정·전송 근거</summary><div>${UI.facts([['관측','기준 응답과 비교해 입력 문자열 반사'],['브라우저 실행','미확인'],['연결 원본','선택한 동일 요청의 원본 ID'],['해석','수동 재현으로 실제 영향을 검증하세요.']])}</div></details>`;
      }
    },
    environment: {
      title:'서버·경로 탐색',nav:'environment',group:'탐색·정보 수집',goal:'관측 근거가 있는 경로를 골라 GET 응답을 확인하기',primary:'선택 경로 GET 확인',
      rationale:[
        '선택 확인을 주 동작으로 두고 전체 실행·재개를 실행 메뉴로 묶었습니다.',
        '환경 관측·설정·저장 관리를 기본 목록과 분리해 후보 표와 응답 읽기 공간을 확보했습니다.',
        '후보 목록과 선택 경로 리더를 나란히 놓아 선택과 검토를 한 흐름으로 연결했습니다.'
      ],
      render(){
        const rows=[['/api/users/me','API 응답 참조','미검증'],['/api/documents/101','API 응답 참조',UI.chip('검토 필요','amber')],['/.well-known/security.txt','공통 탐색','미검증'],['/admin/','공통 탐색','미검증'],['/api-docs','공통 탐색','미검증'],['/api/','공통 탐색','미검증'],['/health','공통 탐색','미검증'],['/metrics','공통 탐색','미검증'],['/robots.txt','공통 탐색','미검증'],['/sitemap.xml','공통 탐색','미검증']];
        const right=`<div class="section stack" style="padding-bottom:7px;gap:7px">${UI.toolbar(UI.btn('선택 경로 GET 확인',{primary:true,action:'environment-get'}),`${stopped('일시정지')}${stopped('중지')}`)}${UI.toolbar(UI.chip('HTTP 200 · 검토 필요','amber'),`<span class="muted small">GET · 비로그인</span>`)}</div>${UI.tabs(['응답','요청','판정 근거'],0)}${UI.code('HTTP/1.1 200 OK\nServer: nginx/1.22.1\nContent-Type: application/json\n\n{\n  "id": 101,\n  "title": "Project notes",\n  "ownerId": "alice"\n}')}<div class="section" style="padding-top:6px">${UI.note('200 응답만으로 경로 노출이나 취약점을 확정하지 않습니다.','amber')}</div>`;
        return `${UI.toolbar(`<div class="row">${UI.field('대상','https://example.test')}${select(['비로그인','Alice','Bob'],'사용 계정')}${UI.chip('SCOPE 안','green')}</div>`,UI.menu('설정·관리',['대상·계정 설정','후보·요청 한도','탐색 결과 저장','탐색 결과 불러오기','변경 기록','URL 목록 복사','분석 초기화']))}
          <details class="disclosure"><summary>관찰 환경 · nginx 단서 및 응답 정보</summary><div>${UI.facts([['Server 헤더','nginx/1.22.1'],['발견 입력','실제 응답에서 참조된 API 경로'],['해석','제품·버전 단서는 추가 확인이 필요합니다.']])}</div></details>
          <div class="split grow" style="grid-template-columns:minmax(0,1.16fr) minmax(0,1fr)">
            ${UI.panel('경로 후보 · 20개',`<div class="section" style="padding:8px 11px">${UI.toolbar(search('경로·상태·발견 근거 검색'),UI.menu('일괄 실행',['후보 목록 전체 GET 확인','미완료 경로 재개']))}</div>${UI.table(['경로','발견 근거','확인 상태'],rows,{selected:1})}`,UI.tabs(['전체 20','미검증','응답 확인'],0),'grow')}
            ${UI.panel('/api/documents/101',right,'','grow')}
          </div>${UI.note('명시적 GET 전송 · 현재 비로그인 · 요청 한도 10회 · 선택·검색만으로 요청을 보내지 않습니다.')}`;
      }
    },
    'exposure.search': {
      title:'외부 노출 조사 · 검색 노출',nav:'exposure.search',group:'탐색·정보 수집',goal:'검색식을 열고 가져온 URL을 SCOPE·근거와 함께 검토하기',primary:'Google에서 열기',
      rationale:[
        '분류 선택·검색식·Google 열기를 하나의 검색 작업으로 묶었습니다.',
        'URL 가져오기·목록 관리와 선택 URL의 Repeater·근거 작업을 구분했습니다.',
        '긴 원문 설명을 사실 요약으로 바꿔 목록과 선택 근거를 동시에 읽도록 구성했습니다.'
      ],
      render(){
        const rows=[['API 문서','https://example.test/swagger/index.html',UI.chip('포함','green'),'미확인'],['API 문서','https://example.test/api-docs',UI.chip('포함','green'),'미확인'],['API 문서','https://example.test/openapi.json',UI.chip('포함','green'),'미확인'],['공개 문서','https://example.test/documents/guide.pdf',UI.chip('포함','green'),'미확인'],['공개 문서','https://example.test/public/manual.pdf',UI.chip('포함','green'),'미확인'],['관리 화면','https://example.test/admin/login',UI.chip('포함','green'),'미확인']];
        const info=`<div class="section stack" style="gap:14px"><div class="small" style="overflow-wrap:anywhere;color:var(--code);line-height:1.9">https://example.test/swagger/index.html</div>${UI.chip('확인 상태 · 미확인','amber')}<div class="divider"></div>${UI.facts([['분류','API 문서'],['SCOPE','현재 범위에 포함'],['발견 경로','사용자가 URL 가져오기'],['연결 검색식','API 문서 검색식']])}<div class="divider"></div>${UI.note('URL 가져오기는 실제 노출 확인이 아닙니다. Repeater에서 응답을 직접 확인하세요.','amber')}<div class="row">${UI.btn('Repeater 준비',{action:'exposure-repeater'})}${UI.btn('근거 복사',{icon:'copy',action:'copy'})}</div><details class="disclosure"><summary>발견 정보 원문</summary><div class="small muted" style="line-height:1.9">설계용 합성 URL 예시입니다.<br>현재 SCOPE 포함 · 확인 상태 미확인<br>검색 결과 URL은 사용자가 직접 가져옵니다.</div></details></div>`;
        return `${domainContext()}${UI.tabs(['검색 노출','CVE 후보'],0)}
          ${UI.toolbar(`<div class="row grow">${select(['API 문서','공개 문서','관리 화면','기타'],'검색식 분류')}<label class="search"><input aria-label="Google 검색식" value="site:example.test (inurl:swagger OR inurl:api-docs OR inurl:openapi)"></label></div>`,`${UI.btn('검색식 복사',{icon:'copy',action:'copy'})}${UI.btn('Google에서 열기',{primary:true,icon:'external',action:'google-search'})}`)}
          <div class="split grow" style="grid-template-columns:minmax(0,1.7fr) minmax(320px,1fr)">${UI.panel('가져온 URL · 6개',UI.table(['분류','URL','SCOPE','확인 상태'],rows,{selected:0}),`${UI.btn('URL 가져오기',{action:'import-urls'})}${UI.menu('목록 관리',['선택 URL 삭제'])}`,'grow')}${UI.panel('선택 URL 근거',info,UI.chip('실제 노출 미확인','amber'),'grow')}</div>
          ${UI.note('Google 검색은 외부 브라우저에서 열립니다. 검색한 URL을 가져와 검토하세요.')}`;
      }
    },
    'exposure.cve': {
      title:'외부 노출 조사 · CVE 후보',nav:'exposure.cve',group:'탐색·정보 수집',goal:'관측 제품에 적용될 수 있는 CVE 조건과 출처를 검토하기',primary:'CVE 후보 조회',
      rationale:[
        '관측 제품은 한 곳에서 요약하고 수동 CPE 수정은 접어 반복 입력을 줄였습니다.',
        '기본 적용 필터를 짧은 칩으로 보여 주고 세부 필터·캐시는 필요할 때 열도록 정리했습니다.',
        '다섯 개 동등한 상세 박스를 적용 조건과 관측 근거의 검토 흐름으로 통합했습니다.'
      ],
      render(){
        const rows=[['CVE-2099-10001',UI.chip('9.8','red'),'원격·무인증','등재',UI.chip('버전 조건 일치','amber')],['CVE-2099-10002',UI.chip('8.1','amber'),'원격·무인증','미등재','버전 조건 일치'],['CVE-2099-10003',UI.chip('7.5','amber'),'미확인','미등재',UI.chip('조건 미확인','')],['CVE-2099-10004','미확인','미확인','미확인',UI.chip('추가 확인','')]];
        const product=`<div class="row" style="gap:10px"><span class="field-label">관측 제품</span><b style="font-size:13px">nginx 1.22.1</b>${UI.chip('응답 Server 헤더','')}<details class="menu"><summary class="btn">수정 ▾</summary><div class="menu-list" style="width:320px;padding:12px"><div class="stack">${UI.field('CPE 공급사','nginx')}${UI.field('제품','nginx')}${UI.field('관측 버전','1.22.1')}<span class="small muted">관측 정보 보완용 · 조회는 직접 실행합니다.</span></div></div></details></div>`;
        const filters=`<div class="row">${UI.chip('CVSS 7.0 이상','blue')}${UI.chip('원격·인증 불필요','blue')}${UI.chip('미확인 포함','')}<details class="menu"><summary class="btn">필터 ▾</summary><div class="menu-list" style="width:260px;padding:12px"><div class="stack">${UI.field('최소 CVSS','7.0')}<label class="small row"><input type="checkbox" checked> 원격·인증 불필요</label><label class="small row"><input type="checkbox"> KEV 등재만</label><label class="small row"><input type="checkbox" checked> 미확인 포함</label></div></div></details></div>`;
        const details=`<div class="section stack" style="gap:11px"><div class="row">${UI.chip('CVSS 9.8','red')}${UI.chip('버전 조건 일치','amber')}<span class="muted small">설계용 후보 예시</span></div>${UI.tabs(['대상 적용 조건','관측 근거'],0).replace('class="tabs"','class="tabs" data-exec-tabs="cve"')}<div class="col" data-exec-content="cve">${cveView(0)}</div><div class="row">${UI.btn('NVD 출처 열기',{icon:'external',action:'cve-source'})}${UI.btn('근거 복사',{icon:'copy',action:'copy'})}</div></div>`;
        return `${domainContext()}${UI.tabs(['검색 노출','CVE 후보'],1)}
          ${UI.toolbar(product,`${UI.btn('CVE 후보 조회',{primary:true,action:'cve-query'})}${stopped('취소')}${UI.menu('더보기',['캐시 보기'])}`)}
          ${UI.toolbar(filters,`<span class="small muted">조회 완료 · NVD / KEV / EPSS <span title="조회 시각과 각 제공자 최신성은 관측·조회 원문 근거에서 확인">ⓘ</span></span>`)}
          <div class="split grow" style="grid-template-columns:minmax(0,1.28fr) minmax(390px,1fr)">${UI.panel('검토할 CVE 후보 · 4개',UI.table(['CVE','CVSS','접근 조건','KEV','대상 판정'],rows,{selected:0}),UI.chip('미확인 조건 포함',''),'grow')}${UI.panel('CVE-2099-10001',details,'','grow')}</div>
          `;
      }
    }
  });
})();
