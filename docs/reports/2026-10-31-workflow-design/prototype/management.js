/* Design-only screens. Fixture data; no production or external transport. */
(() => {
  const U = window.UI || UI;
  window.DESIGNS = window.DESIGNS || {};
  const field = (label, value) => U.field(label, value);
  const textArea = (value, attrs = '') => `<textarea ${attrs} spellcheck="false" style="width:100%;height:100%;min-height:0;resize:none;border:0;background:transparent;color:inherit;font:inherit;line-height:1.65;outline:none;padding:0">${value}</textarea>`;
  const activityRows = [
    ['GET','/api/users/me','검토 중','2개'],
    ['GET','/api/documents/101','검토 중','2개'],
    ['GET','/api/documents/102','미점검','3개'],
    ['POST','/api/documents','미점검','4개'],
    ['GET','/api/projects','검토 중','1개'],
    ['GET','/api/projects/12/notes','미점검','2개'],
    ['GET','/api/admin/users','미점검','3개'],
    ['GET','/api/assets/config','미점검','1개'],
    ['GET','/api/search?q=workbench','검토 중','2개'],
    ['POST','/api/auth/login','미점검','3개'],
    ['GET','/api/reports/2026','미점검','1개'],
    ['GET','/api/notifications','미점검','2개']
  ];
  const evidenceRows = [
    ['문서 상세 · 계정 비교','권한 비교'],['문서 상세 · Alice','원본 요청'],
    ['문서 상세 · Bob','원본 요청'],['문서 상세 · 비로그인','원본 요청'],
    ['문서 목록 · 계정 비교','권한 비교'],['입력값 검사 · 기준','검사 결과'],
    ['입력값 검사 · 변경','검사 결과'],['업무 흐름 · 단계 2','재현 결과']
  ];
  const packet = `HTTP/1.1 200 OK\nContent-Type: application/json; charset=utf-8\nCache-Control: private, no-store\nX-Request-Id: example-101\n\n{\n  "id": 101,\n  "title": "문서 접근 권한 비교",\n  "ownerId": "alice",\n  "project": {\n    "id": 12,\n    "name": "보안 검토"\n  },\n  "content": "화면 설계를 위한 합성 예시입니다.",\n  "updatedAt": "2026-10-31T10:30:00+09:00"\n}`;
  const replayRequest = `GET /api/documents/101 HTTP/1.1\nHost: simple.example.test\nCookie: SID=EXAMPLE_ACCOUNT\nAccept: application/json\n\n===STEP===\nGET /api/documents/{{id|URL}} HTTP/1.1\nHost: simple.example.test\nCookie: SID=EXAMPLE_ACCOUNT\nAccept: application/json\n\n===STEP===\nGET /api/projects HTTP/1.1\nHost: simple.example.test\nCookie: SID=EXAMPLE_ACCOUNT\nAccept: application/json\n`;
  const dashboardRows = [
    ['문서 상세 응답','Alice','정보','응답 관측'],
    ['문서 접근 차이','Bob','정보','직접 확인 필요'],
    ['비로그인 응답 차이','비로그인','정보','직접 확인 필요'],
    ['API 경로 후보','Alice','정보','JS 근거 있음'],
    ['API 명세 후보','비로그인','정보','직접 확인 필요'],
    ['환경 정보 단서','Alice','정보','추가 검토'],
    ['민감 키 패턴 후보','Alice','높음','유효성 미검증'],
    ['도메인 참조','Alice','정보','추가 검토'],
    ['프로젝트 목록','Alice','정보','응답 관측'],
    ['문서 목록','Bob','정보','응답 관측'],
    ['관리자 경로 후보','Bob','정보','접근 거부 관측'],
    ['제한된 경로','비로그인','정보','응답 관측']
  ];
  Object.assign(window.DESIGNS, {
    activity: {
      title: '점검 진행 현황', nav: 'activity', group: '점검 관리·증거',
      goal: '남은 항목을 찾고 근거를 검토한 뒤 완료 상태를 기록합니다.',
      primary: '완료 표시',
      rationale: [
        '목록은 URL·진행 상태·남은 입력에 집중하고 요청 수와 출처는 선택 상세로 모았습니다.',
        '근거 확인과 완료 기록을 한 선택 영역으로 묶어 URL 완료·입력 완료의 대상을 분명히 했습니다.',
        '가져오기·저장·내보내기를 기록 메뉴로 통합해 상단의 상시 버튼 수를 줄였습니다.'
      ],
      render() {
        const context = U.toolbar(
          `<div class="row">${U.chip('추적 중','success')}${U.chip('SCOPE만','neutral')}<span class="muted small">simple.example.test</span></div>`,
          U.menu('기록', ['History 가져오기','기록 저장','기록 불러오기','Excel 내보내기','기록 초기화'])
        );
        const filter = U.toolbar(
          `<div class="row grow"><input aria-label="URL·입력 이름 검색" placeholder="URL·입력 이름 검색" style="flex:1;min-width:200px"><select aria-label="점검 상태"><option>미완료</option><option>전체</option><option>완료</option></select></div>`,
          `<span class="muted small">미완료 12개 · 전체 16개</span>`
        );
        const left = U.panel('URL 점검 목록', `<div style="padding:10px 12px">${filter}</div><div class="grow" style="min-height:0;overflow:auto">${U.table(['메서드','URL','진행','남은 입력'],activityRows,{selected:1})}</div>`, '', 'compact');
        const selected = U.panel('선택 URL', `
          <div class="stack" style="height:100%;min-height:0">
            <div class="col" style="gap:10px;padding:14px 14px 0">
            <div><span class="small muted">GET</span><strong style="display:block;margin-top:5px;overflow-wrap:anywhere">/api/documents/101</strong></div>
            <div style="margin:0 -14px">${U.facts([['진행 상태','검토 중'],['발견 출처','JS 경로 + 관찰 요청'],['연결 요청','2건 · Repeater 준비 1건']])}</div>
            <div class="row">${U.btn('발견 근거',{icon:'file',action:'activity.evidence'})}${U.menu('선택 작업',['Repeater 초안 준비'])}</div>
            </div><div class="divider"></div>
            <div class="row" style="justify-content:space-between;padding:0 14px"><strong>입력 점검</strong><span class="small muted">선택 URL에 포함된 입력</span></div>
            ${U.table(['위치·이름','진행'],[['Path · id','미완료'],['Query · locale','미완료']],{selected:0})}
            <div class="row" style="justify-content:space-between;padding:0 14px"><span class="small muted">Path · id 선택</span>${U.btn('입력 완료 표시',{action:'activity.parameter.complete'})}</div>
            <div class="grow"></div>
            <div style="padding:0 14px 10px">${U.note('URL 전체 완료와 개별 입력 완료는 별도로 기록합니다.','neutral')}</div>
          </div>`, U.btn('완료 표시',{primary:true,icon:'check',action:'activity.complete'}), 'compact');
        return `<div class="col" style="height:100%;gap:10px">${context}${U.tabs(['URL 점검','미확정 경로','계정별 점검','업무별 현황'],0)}<div class="grow" style="min-height:0;display:grid;grid-template-columns:minmax(0,1.9fr) minmax(310px,1fr);gap:12px">${left}${selected}</div></div>`;
      }
    },
    bounty: {
      title:'증거 정리',nav:'bounty',group:'점검 관리·증거',
      goal:'원본 근거를 확인하며 영향과 재현 절차를 작성하고, 가림을 확인해 저장합니다.',
      primary:'증거 ZIP 저장',
      rationale:[
        '문서 저장·미리보기와 증거 항목 편집을 분리해 작업 수준을 구분했습니다.',
        '영향·재현 절차를 하나의 작성 흐름으로 묶고 원문을 옆에서 대조하도록 구성했습니다.',
        '항목별 순서·삭제·메모를 메뉴로 묶고 실행 취소만 바로 접근하게 해 목록 공간을 확보했습니다.'
      ],
      render() {
        const docActions = `<div class="row">${U.btn('가림 미리보기',{icon:'eye',action:'bounty.preview'})}${U.btn('증거 ZIP 저장',{primary:true,icon:'download',action:'bounty.save'})}${U.menu('문서 설정',['가림 설정·본문 포함','작성 도움·저장된 초안','증거 비우기'])}</div>`;
        const title = U.toolbar(`<div class="col grow" style="gap:4px"><input aria-label="증거 문서 제목" value="문서 접근 권한 비교 · 검토 초안" style="width:100%;font-weight:600"><span class="small muted">초안 자동 저장 · 연결한 원본 8개</span></div>`,docActions);
        const listBody = `<div class="col" style="height:100%;gap:0"><div class="col" style="padding:12px;gap:10px"><input placeholder="증거 이름·타입·메모 검색" aria-label="증거 검색">${U.toolbar(U.menu('항목 작업',['메모 편집','위로 이동','아래로 이동','선택 삭제','다시 실행']),U.btn('실행 취소',{icon:'undo',action:'bounty.undo'}))}</div><div class="grow" style="overflow:auto;min-height:0">${U.table(['증거','유형'],evidenceRows,{selected:0})}</div><span class="small muted" style="padding:10px 12px">선택 1개 · Ctrl+Z 실행 취소</span></div>`;
        const writer = `<div class="col" style="height:100%;gap:14px;overflow:auto;padding:14px">
          <div class="row" style="justify-content:space-between"><span class="small muted">선택 근거: 문서 상세 · 계정 비교</span>${U.chip('검토 초안','neutral')}</div>
          <section class="col" style="gap:7px"><strong>실제 영향</strong><div style="height:155px;padding:12px;border:1px solid var(--line,#dfe3e9);border-radius:8px">${textArea('다른 사용자 및 비로그인 응답을 비교했습니다. 접근 정책과 원문 근거를 대조하여 실제 영향을 작성합니다.\n\n현재 내용은 화면 설계를 위한 합성 예시입니다.','aria-label="실제 영향 작성"')}</div></section>
          <section class="col grow" style="min-height:250px;gap:7px"><strong>재현 절차</strong><div class="grow" style="padding:12px;border:1px solid var(--line,#dfe3e9);border-radius:8px">${textArea('1. 예시 대상과 요청을 SCOPE에서 확인합니다.\n2. Alice · Bob · 비로그인 응답을 비교합니다.\n3. 상태 코드와 문서 소유자 값을 원문에서 대조합니다.\n4. 검토한 근거를 연결하고 가림 미리보기를 확인합니다.','aria-label="재현 절차 작성"')}</div></section>
          ${U.note('저장 전 미리보기에서 보고서와 패킷의 민감 정보 가림을 확인하세요.','neutral')}
        </div>`;
        const source = `<div class="col" style="height:100%;min-height:0;gap:10px">${U.tabs(['응답','요청'],0)}${U.facts([['계정','Alice · 문서 소유자'],['응답','200 OK · JSON']])}<div class="grow" style="overflow:auto;min-height:0">${U.code(packet)}</div><details class="disclosure" style="margin:0 12px 12px"><summary>연결 원본 정보</summary><div class="section small muted">GET https://simple.example.test/api/documents/101<br>권한 비교에 사용한 원본 요청·응답을 보존합니다.</div></details></div>`;
        return `<div class="col" style="height:100%;gap:12px">${title}<div class="grow" style="display:grid;grid-template-columns:minmax(235px,.8fr) minmax(390px,1.6fr) minmax(305px,1.1fr);gap:12px;min-height:0">${U.panel('증거 8개',listBody,U.btn('증거 추가',{icon:'plus',action:'bounty.add'}),'compact')}${U.panel('보고서 작성',writer,'','compact')}${U.panel('원문 대조',source,'','compact')}</div></div>`;
      }
    },
    'bounty.replay': {
      title:'요청 재현',nav:'bounty',group:'점검 관리·증거',
      goal:'준비한 요청과 실행 조건을 확인하고 순차 실행 결과를 대조합니다.',
      primary:'순차 실행',
      rationale:[
        '실행에 영향을 주는 대상·쓰기 허용·요청 한도를 상단의 짧은 요약으로 드러냈습니다.',
        '변수 추출과 기대 조건은 필요할 때 펼치게 해 기본 요청 편집 공간을 넓혔습니다.',
        '요청 준비·실행·결과 검토를 분리하고 증거 연결은 결과 문맥에만 두었습니다.'
      ],
      render() {
        const conditions = U.toolbar(`<div class="row">${U.chip('simple.example.test','neutral')}${U.chip('요청 한도 8','neutral')}${U.chip('쓰기 차단','neutral')}</div>`,`<div class="row">${U.btn('실행 설정',{icon:'settings',action:'replay.settings'})}${U.btn('순차 실행',{primary:true,icon:'play',action:'replay.run'})}${U.btn('중지',{icon:'stop',action:'replay.stop',disabled:true})}</div>`);
        const request = `<div class="col" style="height:100%;gap:12px;min-height:0"><div class="row" style="justify-content:space-between;padding:10px 14px 0"><span class="small muted">요청 3단계 · 같은 대상에서 순서대로 실행</span>${U.btn('선택 요청 추가',{icon:'plus',action:'replay.append'})}</div><div class="grow" style="overflow:auto;min-height:0">${U.code(replayRequest)}</div><details class="disclosure" style="margin:0 12px 12px"><summary>변수 추출·단계 기대 조건</summary><div class="stack section">${field('JSON 변수 추출','1.id=$.id')}${field('단계 기대 조건','1.HTTP=200 / 2.HTTP=200 / 3.HTTP=200')}<p class="small muted">추출한 값은 다음 요청의 {{id|URL}} 등에 연결합니다. 기존 입력은 접어도 유지됩니다.</p></div></details></div>`;
        const results = `<div class="col" style="height:100%;gap:12px;min-height:0"><div style="padding:10px 14px 0">${U.toolbar(`<span class="small muted">최근 실행 · 완료 3/3</span>`,U.btn('결과를 증거로',{action:'replay.evidence'}))}</div><div style="flex-shrink:0">${U.table(['단계','요청','HTTP','관찰'],[['1','문서 원본 조회','200','응답 수신'],['2','추출값으로 조회','200','응답 수신'],['3','프로젝트 조회','200','응답 수신']],{selected:1})}</div><div class="divider"></div><div style="padding:0 14px"><strong>단계 2 · 요청·응답</strong><p class="small muted" style="margin:5px 0">GET /api/documents/101</p></div>${U.tabs(['응답','요청'],0)}<div class="grow" style="overflow:auto;min-height:0">${U.code(packet)}</div><div style="padding:0 14px 10px">${U.note('응답 수신은 취약점 확인을 의미하지 않습니다. 상태와 본문을 직접 대조하세요.','neutral')}</div></div>`;
        return `<div class="col" style="height:100%;gap:12px">${conditions}<div class="grow" style="display:grid;grid-template-columns:minmax(0,1.45fr) minmax(360px,1fr);gap:12px;min-height:0">${U.panel('요청 준비',request,'','compact')}${U.panel('실행 결과',results,'','compact')}</div></div>`;
      }
    },
    dashboard: {
      title:'점검 대시보드',nav:'dashboard',group:'점검 관리·증거',
      goal:'현재 실행의 관측 결과를 좁히고 원본 근거를 검토합니다.',
      primary:'원본 보기',
      rationale:[
        '같은 분류 수치를 반복하던 카드와 막대를 한 요약으로 통합했습니다.',
        '분포 그래프를 접히는 상세로 돌려 결과 목록과 원문 근거가 기본 화면의 중심이 되게 했습니다.',
        '전체 점검률로 오해하지 않도록 전송·예산 정보와 관측 결과를 분리했습니다.'
      ],
      render() {
        const overview = U.toolbar(`<div class="row">${U.chip('최근 실행 완료','success')}<span>simple.example.test</span><span class="small muted">전송 8 / 한도 10 · 미확인 1</span></div>`,U.btn('통합 점검 열기',{action:'dashboard.inspect'}));
        const metricLabels = [['관측 경로','18'],['API·명세','4'],['환경 단서','3'],['민감정보 후보','2'],['계정·권한 검토','2'],['미확인·제한','1']];
        const metrics = `<div style="display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px">${metricLabels.map(([label,value],i)=>`<button class="metric" data-action="dashboard.filter.${i}" style="text-align:left;color:inherit"><span class="small muted">${label}</span><strong style="display:block;font-size:25px;margin-top:8px">${value}</strong></button>`).join('')}</div>`;
        const filters = U.toolbar(`<div class="row">${field('계정','전체 결과 계정')}${field('중요도','전체')}${U.chip('관측 경로 18','neutral')}</div>`,U.btn('조건 해제',{action:'dashboard.reset'}));
        const list = `<div class="col" style="height:100%;min-height:0;gap:10px"><div class="grow" style="overflow:auto;min-height:0">${U.table(['내용','계정','중요도','상태'],dashboardRows,{selected:1})}</div><span class="small muted" style="padding:0 12px 10px">18개 중 12개 표시 · 결과를 선택해 근거를 확인하세요.</span></div>`;
        const detail = `<div class="col" style="height:100%;min-height:0;gap:14px;padding:14px"><div><span class="small muted">선택 결과</span><strong style="display:block;margin-top:6px">문서 접근 응답 차이</strong></div><div style="margin:0 -14px">${U.facts([['요청','GET /api/documents/101'],['관측 계정','Bob'],['상태','직접 확인 필요'],['확실성','응답 차이 관측']])}</div><div class="divider"></div><strong>근거 요약</strong><p style="line-height:1.7;margin:0">문서 소유자와 다른 계정의 응답 상태가 다릅니다. 접근 정책과 원문 본문을 함께 확인해 의미를 판단하세요.</p>${U.note('관측된 응답 차이는 실제 취약점 판정이 아닙니다.','neutral')}<details class="disclosure"><summary>원본·처리 정보</summary><div class="section small muted">대표 원본 계정 Alice · 비교 계정 Bob<br>응답 관측 · 가림 처리된 합성 예시<br>대표 원본을 열면 해당 요청·응답을 확인할 수 있습니다.</div></details><div class="grow"></div></div>`;
        const distribution = `<details class="disclosure"><summary>분포 자세히</summary><div class="section grid2">${U.facts([['관측 경로','18'],['API·명세','4'],['환경 단서','3']])}${U.facts([['높음 후보','1'],['중간 후보','0'],['낮음·정보 후보','1']])}</div></details>`;
        return `<div class="col" style="height:100%;gap:12px">${overview}${metrics}${filters}<div class="grow" style="display:grid;grid-template-columns:minmax(0,1.85fr) minmax(320px,1fr);gap:12px;min-height:0">${U.panel('실행 결과',list,'','compact')}${U.panel('선택 항목 근거',detail,`<div class="row">${U.btn('원본 보기',{primary:true,icon:'file',action:'dashboard.original'})}${U.menu('다음 작업',['Repeater 준비','증거 추가'])}</div>`,'compact')}</div>${distribution}</div>`;
      }
    },
    data: {
      title:'데이터 변환·비교',nav:'data',group:'데이터 도구',
      goal:'입력에 필요한 변환을 적용하고 원문과 결과를 나란히 대조합니다.',
      primary:'변환',
      rationale:[
        '변환 방식을 공통 작업줄에서 고르고 실행하게 해 입력과 출력의 관계를 드러냈습니다.',
        '복사는 출력 헤더에, 출력 재사용은 출력 작업 메뉴에 묶어 역할을 분리했습니다.',
        '모드별로 필요한 기능만 보여 주어 JWT 가져오기와 변환 작업을 섞지 않았습니다.'
      ],
      render() {
        const input = '{\n  "target": "simple.example.test",\n  "purpose": "문서 접근 권한 비교",\n  "documentId": 101,\n  "account": "Alice"\n}';
        const output = btoa(String.fromCharCode(...new TextEncoder().encode(input)));
        const selector = `<select aria-label="변환 방식" data-codec-operation style="min-width:230px"><optgroup label="인코딩·디코딩"><option>Base64 인코딩</option><option>Base64 디코딩</option><option>URL 인코딩 (form)</option><option>URL 디코딩 (form)</option><option>Base64URL 인코딩</option><option>Base64URL 디코딩</option><option>Hex 인코딩</option><option>Hex 디코딩</option></optgroup><optgroup label="해시"><option>SHA-256</option></optgroup><optgroup label="압축"><option>Gzip → Base64</option><option>Base64 → Gzip 해제</option></optgroup></select>`;
        const commands = U.toolbar(`<div class="row"><span class="small muted">변환 방식</span>${selector}${U.btn('변환',{primary:true,icon:'arrow',action:'codec.convert'})}</div>`,`<span class="small muted">로컬 변환 · 요청 전송 없음</span>`);
        const source = `<div class="col" style="height:100%;gap:10px;min-height:0;padding:12px 14px"><div class="grow" style="min-height:0;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:14px;padding:4px 1px">${textArea(input,'aria-label="변환 입력" data-codec-input')}</div><span class="small muted">UTF-8 텍스트</span></div>`;
        const result = `<div class="col" style="height:100%;gap:10px;min-height:0;padding:12px 14px"><div class="grow" style="min-height:0;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:14px;padding:4px 1px">${textArea(output,'aria-label="변환 출력" data-codec-output readonly')}</div><span class="small muted">Base64 · 출력 결과</span></div>`;
        return `<div class="col" style="height:100%;gap:12px">${U.tabs(['인코딩·변환','JWT 내용 확인','응답·데이터 비교'],0)}${commands}<div class="grow" style="display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:12px;min-height:0">${U.panel('입력',source,'','compact')}${U.panel('출력',result,`<div class="row">${U.btn('복사',{icon:'copy',action:'codec.copy'})}${U.menu('출력 작업',[{label:'입력으로 다시 사용',action:'codec.reuse'}])}</div>`,'compact')}</div>${U.note('입력과 출력은 각각 보존합니다. 출력 재사용은 직접 선택할 때만 입력에 반영합니다.','neutral')}</div>`;
      }
    }
  });
})();
