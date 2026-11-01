"""Build a separate presentation proposal from the unchanged saved-result HTML."""
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "layout-view/Workbench_Simple_1.4.21_Results_Layout_KR.html"
original = SOURCE.read_bytes()
assert hashlib.sha256(original).hexdigest() == "b3939ff7be194077078192c84f8a6feb2120adfcc26628e29fd025490a8cf3f0"
html = original.decode()
data = json.loads(html.split('<script type="application/json" id="layout-data">')[1].split('</script>')[0])
features = data['mapper']['metadata']['features']
titles = [
    '홈 화면', '연결 확인', '공통 요청', '정적 API 명세', '대시보드', '잔액 조회', '관리자 화면',
    '대조 경로 01', '대조 경로 02', '대조 경로 03', '환경 설정 파일',
    '잔액 입력 변형 01', '잔액 입력 변형 02', '잔액 입력 변형 03', '대조 경로 04', '대조 경로 05',
    '가맹점 로그인', '로그인', '회원가입 화면', 'API 문서 화면', '채용 안내', '블로그',
    '개인정보 안내', '이용약관', '준법 안내', '검색 로봇 규칙 후보', '사이트맵 후보',
    'AI 대화 경로 관측', 'API 인증 연결 화면', '가맹점 가입 화면', 'OpenAPI 명세 후보',
    'Swagger 명세 후보', 'API 문서 후보 01', '보안 연락처 후보', 'Swagger 화면 후보',
    'API 문서 후보 02', 'API 문서 후보 03', 'Swagger UI 후보', '관리자 경로 후보',
    'API 진입점 후보', '지표 경로 후보', 'ReDoc 문서 후보', '상태 경로 후보',
]
groups = [
    ('business', '핵심 업무', [4, 5, 6, 27]),
    ('account', '계정·인증', [17, 16, 18, 29]),
    ('pages', '화면·안내', [0, 20, 21, 22, 23, 24]),
    ('docs', 'API·문서 탐색', [3, 19, 28, 30, 31, 32, 34, 35, 36, 37, 41]),
    ('ops', '환경·운영 탐색', [1, 10, 25, 26, 33, 38, 39, 40, 42]),
    ('inputs', '입력 변형 관측', [11, 12, 13]),
    ('control', '존재하지 않는 경로 확인', [7, 8, 9, 14, 15]),
    ('common', '공통 요청', [2]),
]
assert len(features) == len(titles) == 43
assert sorted(i for _, _, indices in groups for i in indices) == list(range(43))
aliases = {f['id']: {'title': title, 'group': next(k for k, _, ids in groups if i in ids)}
           for i, (f, title) in enumerate(zip(features, titles))}
group_data = [{'key': k, 'title': t, 'ids': [features[i]['id'] for i in ids]} for k, t, ids in groups]

css = r'''
.proposal-bar{display:flex;align-items:center;gap:14px;flex-wrap:wrap;padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:var(--surface);flex:none}
.proposal-intro{flex:1;min-width:180px}.proposal-intro strong{display:block;font-size:14px}.proposal-intro span{font-size:11px;color:var(--muted)}
.view-switch{display:flex;gap:4px;padding:3px;border:1px solid var(--line);border-radius:7px;background:var(--raised)}
.view-switch button{border:0;background:none;font-size:12px;min-height:29px}.view-switch button.active{background:var(--selected);color:var(--accent)}
.mapper-proposal{display:grid;grid-template-columns:280px minmax(0,1fr);gap:12px;min-height:0;flex:1}
.feature-pane{min-height:400px}.feature-scroll{overflow:auto;padding:6px;min-height:0;flex:1}
.feature-row{width:100%;display:flex;align-items:center;gap:8px;border:1px solid transparent;background:transparent;min-height:44px;border-radius:5px;padding:7px 10px;text-align:left;white-space:normal;color:var(--ink)}
.feature-row:hover{background:var(--hover)}.feature-row.selected{background:var(--selected);border-color:var(--selected);box-shadow:inset 3px 0 var(--accent)}
.feature-label{min-width:0;flex:1}.feature-label strong{font-weight:500;font-size:13px;display:block}.feature-label small{display:block;font-size:10px;color:var(--muted);margin-top:1px}
.feature-num{font-variant-numeric:tabular-nums;font-size:11px;color:var(--muted);background:var(--raised);padding:2px 6px;border-radius:4px;white-space:nowrap}.selected .feature-num{color:var(--accent);background:var(--surface)}
.feature-group{border:0;margin-top:5px}.feature-group summary{display:flex;align-items:center;gap:7px;cursor:pointer;padding:9px 8px;font-size:11px;font-weight:600;color:var(--muted);list-style:none}.feature-group summary::-webkit-details-marker{display:none}
.feature-group summary::before{content:'›';width:9px;font-size:15px;line-height:1;transform-origin:center}.feature-group[open]>summary::before{transform:rotate(90deg)}
.feature-group summary span:last-child{margin-left:auto;font-weight:400;font-size:10px}.feature-group summary:hover{color:var(--ink)}
.feature-group .feature-row{padding-left:18px}.feature-group summary .group-title{min-width:0}.feature-hint{padding:9px 12px;border-top:1px solid var(--line);font-size:10px;color:var(--muted)}
.feature-scroll.flat .feature-row{min-height:36px;padding-top:5px;padding-bottom:5px}.feature-scroll.tiles{display:grid;grid-template-columns:1fr 1fr;align-content:start;gap:6px}.feature-scroll.tiles .feature-row{min-height:86px;display:flex;align-items:flex-start;flex-direction:column;border-color:var(--line);padding:10px}.feature-scroll.tiles .feature-row.selected{border-color:var(--accent)}
.mapper-request-table{max-height:450px}.mapper-request-table .table-wrap{flex:none;max-height:370px}
.observation-box{padding:16px}.observation-heading{display:flex;align-items:center;gap:10px;margin-bottom:10px}.observation-heading h2{font-size:16px}
.observation-facts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-bottom:15px}.observation-facts div{background:var(--raised);border:1px solid var(--line);padding:10px;border-radius:6px}.observation-facts small{display:block;color:var(--muted);font-size:11px}.observation-facts strong{display:block;margin-top:3px;font-weight:500}
.original-disclosure{border-top:1px solid var(--line);padding-top:10px}.original-disclosure summary{cursor:pointer;color:var(--accent);font-size:12px}.original-disclosure .kv{margin-top:12px}
.proposal-note{font-size:11px;color:var(--muted);line-height:1.8;padding:0 2px;flex:none}
@media(max-width:1000px){.mapper-proposal{grid-template-columns:245px minmax(0,1fr)}.observation-facts{grid-template-columns:1fr}}
@media(max-width:760px){.mapper-proposal{grid-template-columns:minmax(0,1fr);flex:none}.feature-pane{min-height:0;max-height:420px}.proposal-intro{min-width:100%}.view-switch{width:100%}.view-switch button{flex:1}.observation-facts{grid-template-columns:repeat(3,minmax(0,1fr))}.proposal-note{padding-bottom:10px}.mapper-request-table{max-height:400px}.brand{display:none}}
'''
js = r'''
const MAP_ALIAS=__ALIASES__;
const MAP_GROUPS=__GROUPS__;
const MAP_DISPLAY={mode:'grouped',open:new Set(['business','account'])};
const mapperPackets=id=>D.mapper.metadata.packets.filter(p=>p.primary===id);
const mapperTitle=id=>(MAP_ALIAS[id]||{}).title||'분류되지 않은 관측';
function mapperFeatureButton(f){
  const packets=mapperPackets(f.id);
  const a=MAP_ALIAS[f.id],group=MAP_GROUPS.find(g=>g.key===a.group);
  const hint=MAP_DISPLAY.mode==='tiles'?group.title+' · '+f.state:'';
  return '<button class="feature-row'+(UI.feature===f.id?' selected':'')+'" data-feature="'+E(f.id)+'" aria-pressed="'+(UI.feature===f.id)+'"><span class="feature-label"><strong>'+E(a.title)+'</strong>'+(hint?'<small>'+E(hint)+'</small>':'')+'</span><span class="feature-num" aria-label="관측 요청 '+packets.length+'건">'+packets.length+'건</span></button>';
}
function mapperView(){
  const m=D.mapper.metadata,all=m.packets,features=m.features,q=UI.q.toLocaleLowerCase();
  const matches=p=>!q||(mapperTitle(p.primary)+' '+JSON.stringify(p)).toLocaleLowerCase().includes(q);
  const rows=all.filter(p=>(UI.feature==='all'||p.primary===UI.feature)&&matches(p));
  const s=selected('mapper',rows),r=s.row;
  const visible=features.filter(f=>!q||(mapperTitle(f.id)+' '+JSON.stringify(f)).toLocaleLowerCase().includes(q)||mapperPackets(f.id).some(matches));
  let list='<button class="feature-row'+(UI.feature==='all'?' selected':'')+'" data-feature="all"><span class="feature-label"><strong>전체 관측 그룹</strong></span><span class="feature-num">'+all.length+'건</span></button>';
  if(MAP_DISPLAY.mode==='grouped'){
    list+=MAP_GROUPS.map(g=>{
      const fs=visible.filter(f=>g.ids.includes(f.id));if(!fs.length)return '';
      return '<details class="feature-group" data-group="'+g.key+'"'+((q||MAP_DISPLAY.open.has(g.key))?' open':'')+'><summary><span class="group-title">'+E(g.title)+'</span><span>'+fs.length+'개</span></summary>'+fs.map(mapperFeatureButton).join('')+'</details>';
    }).join('');
  }else list+=visible.map(mapperFeatureButton).join('');
  const modes=[['grouped','A 그룹형 · 추천'],['flat','B 한 줄형'],['tiles','C 카드형']];
  const top='<div class="proposal-bar"><div class="proposal-intro"><strong>기능명 중심으로 보기</strong><span>실제 관측 데이터로 비교하는 목록 표시 시안</span></div><div class="view-switch" role="group" aria-label="목록 표시 방식">'+modes.map(([k,t])=>'<button data-viewmode="'+k+'" class="'+(MAP_DISPLAY.mode===k?'active':'')+'" aria-pressed="'+(MAP_DISPLAY.mode===k)+'">'+t+'</button>').join('')+'</div></div>';
  const heading=UI.feature==='all'?'전체 관측':mapperTitle(UI.feature);
  const pane=card('기능별 보기 · 43개','<div class="feature-scroll '+MAP_DISPLAY.mode+'" data-scroll="feature-proposal">'+list+'</div><div class="feature-hint">숫자는 기능에 연결된 관측 요청 수입니다.</div>','feature-pane');
  const cols=[{label:'#',key:'id'},{label:'기능명',value:p=>mapperTitle(p.primary)},{label:'메서드',key:'method'},{label:'HTTP',html:p=>status(p.status)},{label:'실행',key:'run'}];
  const details=r?'<div class="observation-box"><div class="observation-heading"><h2>'+E(mapperTitle(r.primary))+'</h2>'+badge('미점검')+'</div><div class="observation-facts"><div><small>응답 상태</small><strong>'+status(r.status)+'</strong></div><div><small>요청 메서드</small><strong>'+E(r.method)+'</strong></div><div><small>응답 크기</small><strong>'+count(r.responseBytes)+' B</strong></div></div><details class="original-disclosure"><summary>원본 정보 보기</summary>'+kv([['URL',r.url],['관측 ID',r.id],['기능 ID',r.primary],['분류 근거',r.confidence],['원래 기능명',(features.find(f=>f.id===r.primary)||{}).name]])+'</details></div>':empty();
  return top+line(['<strong>관측 135건</strong>','<span>자동 분류된 관측 그룹 43개</span>','<span class="muted">표시명 제안 · 점검 상태 유지</span>'])+'<div class="mapper-proposal">'+pane+'<div class="stack">'+card(heading+' · 요청 '+rows.length+'건',table('mapper',rows,cols,s.index),'table-card mapper-request-table')+card('선택한 관측',details)+'</div></div><p class="proposal-note">목록에서는 기능명과 요청 수를 먼저 보여줍니다. 문서·경로 후보와 입력 변형은 접어 둘 수 있고, 원본 URL은 선택 상세의 ‘원본 정보 보기’에서 확인합니다.</p>';
}
ROOT.addEventListener('click',event=>{const button=event.target.closest('[data-viewmode]');if(button){MAP_DISPLAY.mode=button.dataset.viewmode;render()}});
ROOT.addEventListener('toggle',event=>{const group=event.target.dataset.group;if(group){if(event.target.open)MAP_DISPLAY.open.add(group);else MAP_DISPLAY.open.delete(group)}},true);
'''.replace('__ALIASES__', json.dumps(aliases, ensure_ascii=False)).replace('__GROUPS__', json.dumps(group_data, ensure_ascii=False))

start = html.index('function mapperView(){')
end = html.index('function jsView(){', start)
html = html[:start] + js + '\n' + html[end:]
html = html.replace('</style>', css + '\n</style>', 1)
html = html.replace('<title>Workbench Simple 1.4.21 · 도구별 실행 결과</title>', '<title>기능 목록 가독성 · 3가지 표시 시안</title>')
html = html.replace('<body data-theme="light">', '<body data-theme="dark">')
html = html.replace('저장된 실행 결과</span>', '기능 목록 표시 시안</span>', 1)
html = html.replace('VulnBank · 127.0.0.1:24904', 'VulnBank · 저장된 관측')
html = html.replace('<span class="muted">대상</span> &nbsp;127.0.0.1:24904', '기능명 중심 표시')
html = html.replace('placeholder="현재 도구의 URL · 값 검색"', 'placeholder="기능명 · 원본 값 검색"')
html = html.replace('id="theme-toggle" aria-label="다크 화면으로 전환" title="테마 전환">☾', 'id="theme-toggle" aria-label="라이트 화면으로 전환" title="테마 전환">☀')
last = html.rindex('\nrender();')
html = html[:last] + "\nUI.page='mapper';UI.feature=" + json.dumps(features[5]['id']) + ';' + html[last:]
assert '__LAYOUT_DATA__' not in html
(HERE / 'Mapper_Readability_Proposal_KR.html').write_text(html, encoding='utf-8')
assert SOURCE.read_bytes() == original
print('Built separate proposal; preserved all43 feature IDs and135 original observations.')
