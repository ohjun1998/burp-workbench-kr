(function(){
 var groups=[
 ['트래픽',[['traffic','요청·응답 목록','chart'],['mapper','기능 자동 매핑','tree']]],
 ['탐색·정보 수집',[['js','JS에서 API 찾기','code'],['environment','서버·경로 탐색','layers'],['exposure.search','외부 노출·CVE 조사','search']]],
 ['보안 분석·검증',[['auth','계정 권한 비교','user'],['response.recon','응답 보안 분석','shield'],['payload','입력값 보안 검사','lock']]],
 ['데이터 도구',[['data','데이터 변환·비교','grid']]],
 ['점검 관리·증거',[['activity','점검 진행 현황','check'],['bounty','재현·증거 정리','file'],['dashboard','점검 대시보드','chart']]]
 ];
 var params=new URLSearchParams(location.search),screen=params.get('screen')||'traffic',theme=params.get('theme')||'light';
 function render(){
  var data=window.DESIGNS[screen];if(!data){document.getElementById('main').innerHTML='<div class="empty">화면을 준비하고 있습니다.</div>';window.RENDER_READY=false;return}
  document.documentElement.dataset.theme=theme;
  document.title=data.title+' · UX 설계 시안';
  document.getElementById('menu-toggle').innerHTML=UI.icon('menu');document.getElementById('theme-toggle').innerHTML=UI.icon(theme==='dark'?'sun':'moon');
  document.getElementById('breadcrumb').innerHTML='<span>'+UI.enc(data.group||'Workbench')+'</span><span>›</span><b>'+UI.enc(data.title)+'</b>';
  document.getElementById('sidebar').innerHTML=groups.map(function(g){return '<section class="nav-group"><div class="nav-label"><span>'+g[0]+'</span></div>'+g[1].map(function(x){var active=data.nav===x[0]||screen===x[0]||screen==='exposure.cve'&&x[0]==='exposure.search';return '<a class="nav-link '+(active?'active':'')+'" href="?screen='+x[0]+'&theme='+theme+'" data-screen="'+x[0]+'">'+UI.icon(x[2])+'<span>'+x[1]+'</span></a>'}).join('')+'</section>'}).join('')+'<div class="sidebar-bottom"><b>example.test</b><br>현재 프로젝트 · 수집 범위 설정됨</div>';
  document.getElementById('main').innerHTML=data.render();
  document.getElementById('footer-label').textContent=data.title+' · '+(theme==='dark'?'다크':'라이트');
  window.CURRENT_SCREEN=screen;window.RENDER_READY=true;document.body.dataset.screen=screen;
  // Keep the selected tab within a fixed panel; nested helper tabs remain local.
  document.querySelectorAll('details.menu').forEach(function(d){d.addEventListener('toggle',function(){if(d.open)document.querySelectorAll('details.menu').forEach(function(other){if(other!==d)other.open=false})})});
 }
 function navigate(next){if(!window.DESIGNS[next])return;screen=next;history.replaceState(null,'','?screen='+screen+'&theme='+theme);render()}
 function toast(text){var box=document.getElementById('toast');box.textContent=text;box.classList.add('visible');clearTimeout(window.TOAST_TIMER);window.TOAST_TIMER=setTimeout(function(){box.classList.remove('visible')},2400)}
 document.addEventListener('click',function(e){
  var nav=e.target.closest('a.nav-link[data-screen]');if(nav){e.preventDefault();navigate(nav.dataset.screen);return}
  var tab=e.target.closest('[data-tab]');if(tab){var labels={'CVE 후보':'exposure.cve','검색 노출':'exposure.search','요청 준비':'payload','실행·결과':'payload.results','증거 정리':'bounty','요청 재현':'bounty.replay'};var t=tab.textContent.trim();var route=Object.keys(labels).find(function(x){return t===x||t.startsWith(x+' ')});if(route){navigate(labels[route]);return}tab.parentNode.querySelectorAll('[data-tab]').forEach(function(x){x.classList.toggle('active',x===tab);x.setAttribute('aria-selected',x===tab)});toast('보기 전환 설계 예시입니다. 실제 결과 데이터는 변경하지 않습니다.');return}
  var row=e.target.closest('tbody tr');if(row){row.parentNode.querySelectorAll('tr').forEach(function(x){x.classList.toggle('selected',x===row)});return}
  var action=e.target.closest('[data-action]');if(!action)return;
  var key=action.dataset.action;
  if(key==='nav-toggle'){document.body.classList.toggle('nav-collapsed');return}
  if(key==='theme'){theme=theme==='light'?'dark':'light';history.replaceState(null,'','?screen='+screen+'&theme='+theme);render();return}
  if(key==='close-dialog'){document.getElementById('explain').close();return}
  if(key==='explain'){var data=window.DESIGNS[screen];document.getElementById('explain-body').innerHTML='<h2>'+UI.enc(data.title)+'</h2><p><b>주 작업</b> · '+UI.enc(data.goal)+'</p><ul>'+data.rationale.map(function(t){return '<li>'+UI.enc(t)+'</li>'}).join('')+'</ul><p class="muted small">클릭 흐름을 확인하는 설계 시안입니다. 기존 확장의 처리 기능은 연결하지 않았습니다.</p>';document.getElementById('explain').showModal();return}
  if(window.DESIGNS[key]){navigate(key);return}
  if(key==='accounts'||key==='account-candidates'){navigate('auth.accounts');return}
  if(key==='account-close'){navigate('auth');return}
  if(key==='payload-evidence'||key==='activity.evidence'||key==='replay.evidence'){navigate('bounty');return}
  if(key==='auth-prepare'){document.getElementById('explain-body').innerHTML='<h2>비교 준비</h2><p>선택 요청과 역할 연결을 확인한 뒤 실제 비교를 시작하는 흐름입니다.</p><ul><li>GET /api/documents/101 · example.test</li><li>Alice · Bob · 비로그인 연결 상태 확인</li><li>판정 정책과 쓰기 요청 조건은 실행 전에 검토</li></ul><p class="muted small">설계 시안이므로 대상 요청을 전송하지 않습니다.</p>';document.getElementById('explain').showModal();return}
  if(key==='codec.convert'){
   var input=document.querySelector('[data-codec-input]'),output=document.querySelector('[data-codec-output]'),operation=document.querySelector('[data-codec-operation]').value;
   try{var value=input.value;
    if(operation==='Base64 인코딩')output.value=btoa(unescape(encodeURIComponent(value)));
    else if(operation==='Base64 디코딩')output.value=decodeURIComponent(escape(atob(value)));
    else if(operation==='URL 인코딩 (form)')output.value=encodeURIComponent(value).replace(/%20/g,'+');
    else if(operation==='URL 디코딩 (form)')output.value=decodeURIComponent(value.replace(/\+/g,' '));
    else {toast('선택한 방식의 입력·출력 배치를 확인하는 시안입니다. 실제 확장 엔진은 연결하지 않았습니다.');return}
    toast('시안 내 로컬 변환 결과입니다. 네트워크 요청은 없습니다.');
   }catch(error){toast('입력 형식을 확인하세요.')}return
  }
  if(key==='codec.reuse'){var output=document.querySelector('[data-codec-output]'),input=document.querySelector('[data-codec-input]');if(input&&output){input.value=output.value;toast('현재 출력을 입력으로 가져왔습니다.')}return}
  if(key==='codec.copy'){var result=document.querySelector('[data-codec-output]');if(result){result.focus();result.select();toast('출력 결과를 선택했습니다. Ctrl+C로 복사할 수 있습니다.')}return}
  if(key.indexOf('dashboard.filter.')===0){document.querySelectorAll('.metric').forEach(function(x){x.classList.toggle('active',x===action)});toast('분류를 선택한 화면 상태입니다. 실제 점검 데이터는 연결하지 않았습니다.');return}

  if(key==='results'){navigate('payload.results');return}
  if(key==='prepare'){navigate('payload');return}
  if(key==='evidence'){navigate('bounty');return}
  var parent=action.closest('details.menu');if(parent)parent.open=false;
  toast('「'+action.textContent.trim()+'」 작업 흐름 예시입니다. 시안에서는 실제 요청·조회·저장을 실행하지 않습니다.');
 });
 document.addEventListener('keydown',function(e){if(e.key==='Escape')document.querySelectorAll('details.menu').forEach(function(x){x.open=false});if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();var input=document.querySelector('input[type=search]');if(input)input.focus()}});
 window.DESIGN_NAVIGATE=navigate;window.DESIGN_RENDER=render;render();
})();
