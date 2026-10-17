(() => {
 'use strict';
 const $ = s => document.querySelector(s);
 const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const qp = new URLSearchParams(location.search);
 let user = null, csrf = '', page = qp.get('view') || 'feed', category = 'all', generation = 0, editing = null, labMode = 'vuln';
 const cp = p => '/api/community/fixed' + p, wp = p => '/api/workspace/fixed' + p, ap = p => '/api/access/fixed/' + p;
 const state = s => ({PENDING:'검토 대기',APPROVED:'수락',REJECTED:'거절·반려',CANCELLED:'취소',RESOLVED:'처리 완료',OPEN:'신청 가능',CLOSED:'마감',ACTIVE:'활성',SUSPENDED:'정지'}[s] || s);
 const badge = s => `<span class="state">${esc(state(s))}</span>`;
 const title = t => `<div class="toolbar"><h2>${esc(t)}</h2><span class="meta">${user ? esc(user.username) + ' · ' + esc(user['role']) : '비회원'}</span></div>`;
 const empty = t => `<p class="empty">${esc(t)}</p>`;
 const nav = (p,t) => `<button type="button" data-page="${p}">${esc(t)}</button>`;
 const table = (headers, rows) => `<div class="table-wrap"><table class="messages"><thead><tr>${headers.map(t=>`<th>${esc(t)}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`;
 function toast(text) { $('#toast').textContent = text; $('#toast').style.display = 'block'; setTimeout(()=>$('#toast').style.display='none',3000); }
 function updateUser(value) {
  user = value ? {id:value.ID ?? value.id, username:value.USERNAME ?? value.username, role:value.ROLE ?? value.role, email:value.EMAIL ?? value.email} : null;
  $('#who').textContent = user ? user.username + ' · ' + ({ADMIN:'관리자',USER:'일반 사용자',MODERATOR:'게시판 운영자'}[user['role']] || user['role']) : '비회원';
  $('#admin-menu').hidden = user?.role !== 'ADMIN'; $('#logout').hidden = !user;
 }
 async function readResponse(response,path,method,identity) {
  const raw=await response.text();$('#trace').textContent=`${method} ${path}\nHTTP ${response.status}\n${raw}`;
  let data;try{data=JSON.parse(raw);}catch{data=raw;}
  if(!response.ok){if(response.status===401&&identity===csrf){csrf='';updateUser(null);}const error=new Error(data.error||'HTTP '+response.status);error.status=response.status;throw error;}
  return data;
 }
 async function request(path,options={}) {
  const headers={...options.headers},identity=csrf;
  if(options.method==='POST'&&!options.noCsrf)headers['X-CSRF-Token']=csrf;
  const response=await fetch(path,{...options,headers,credentials:'same-origin',cache:'no-store'});
  return readResponse(response,path,options.method||'GET',identity);
 }
 async function post(path,fields={},options={}) {
  const headers={'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},identity=csrf;
  if(!options.noCsrf)headers['X-CSRF-Token']=csrf;
  const response=await fetch(path,{method:'POST',body:new URLSearchParams(fields),headers,credentials:'same-origin',cache:'no-store'});
  return readResponse(response,path,'POST',identity);
 }
 async function restore() {
  try {const data=await request('/api/access/me');csrf=data.csrf;updateUser(data.user);}
  catch {csrf='';updateUser(null);}
 }
 function requireUser() { if(!user)throw Error('로그인 후 이용하세요. 상단에서 계정을 선택하고 로그인할 수 있습니다.'); }
 function requireAdmin() { requireUser();if(user['role']!=='ADMIN')throw Error('관리자 권한이 필요한 메뉴입니다.'); }
 async function login(event) {
  event?.preventDefault();generation++;
  try {const d=await post('/api/auth/login',{username:$('#account').value,password:$('#password').value});csrf=d.csrf;updateUser(d.user);editing=null;if(page==='login')page='feed';toast('로그인했습니다.');await render();}
  catch(e){await restore();toast(e.message);await render();}
 }
 function listPosts(rows) {
  return rows.map(p=>`<article class="post"><div class="posthead"><span class="tag">${esc(p.CATEGORY)} · ${esc(p.VISIBILITY)}</span><span class="meta">${esc(p.USERNAME)} · #${p.ID}</span></div><h3><button class="text-link" data-page="post:${p.ID}">${esc(p.TITLE)}</button></h3><div class="actions">${nav('post:'+p.ID,'게시글 보기')}</div></article>`).join('') || empty('게시글이 없습니다.');
 }
 async function feed() {
  const rows=await request(cp('/posts'));$('#count').textContent=rows.length+'개의 게시글';
  return title('최신 게시글')+`<form id="searchform" class="toolbar"><input name="q" placeholder="게시글 검색" aria-label="게시글 검색"><button class="secondary">검색</button>${nav('write','글쓰기')}</form><div id="post-list">${listPosts(rows.filter(p=>category==='all'||p.CATEGORY===category))}</div>`;
 }
 function editor(p=null) {
  requireUser();editing=p;
  return title(p?'게시글 수정':'게시글 작성')+`<form id="postform" class="panel editor"><label>제목<input name="title" maxlength="200" value="${esc(p?.TITLE||'')}" required></label><div class="row"><label>게시판<select name="category">${[['general','자유 게시판'],['questions','질문 게시판'],['security','보안 게시판']].map(([v,t])=>`<option value="${v}" ${p?.CATEGORY===v?'selected':''}>${t}</option>`).join('')}</select></label><label>공개 범위<select name="visibility">${[['PUBLIC','전체 공개'],['MEMBERS','회원 전용'],['PRIVATE','비공개']].map(([v,t])=>`<option value="${v}" ${p?.VISIBILITY===v?'selected':''}>${t}</option>`).join('')}</select></label></div><label>본문<textarea name="body" maxlength="4000" required>${esc(p?.BODY||'')}</textarea></label><button class="primary">저장</button></form>`;
 }
 async function detail(id) {
  const p=await request(cp('/posts/'+id));
  const manage = user && (user['id']===p.OWNER_ID || user['role']==='ADMIN' || (user['role']==='MODERATOR'&&p.CATEGORY==='security'&&p.VISIBILITY!=='PRIVATE'));
  const comments=p.comments.map(c=>`<div class="comment"><small>${esc(c.USERNAME)} · #${esc(c.ID)}</small><div class="body">${esc(c.BODY)}</div><div class="comment-tools">${user&&(user['id']===c.OWNER_ID||user['role']==='ADMIN')?`<button data-comment-edit="${esc(c.ID)}" data-body="${esc(c.BODY)}">수정</button><button data-comment-delete="${esc(c.ID)}">삭제</button>`:''}${user?`<button data-report-type="COMMENT" data-report-id="${esc(c.ID)}">신고</button>`:''}</div></div>`).join('');
  return title('게시글 상세')+`<article class="post"><div class="posthead"><span class="tag">${esc(p.CATEGORY)} · ${esc(p.VISIBILITY)}</span><span class="meta">${esc(p.USERNAME)} · #${p.ID}</span></div><h2>${esc(p.TITLE)}</h2><div class="body">${esc(p.BODY)}</div><div class="actions">${nav('feed','목록')}${manage?`<button data-edit="${p.ID}">수정</button><button class="danger" data-delete="${p.ID}">삭제</button>`:''}${user?`<button data-report-type="POST" data-report-id="${p.ID}">신고</button>`:''}</div><section class="comments"><h3>댓글 ${p.comments.length}</h3>${comments||empty('아직 댓글이 없습니다.')}${user?'<form id="commentform" class="editor"><label>댓글<textarea name="body" maxlength="2000" required></textarea></label><button class="primary">댓글 등록</button></form>':empty('로그인하면 댓글을 작성할 수 있습니다.')}</section><section class="comments"><h3>첨부파일</h3>${p.files.map(f=>`<p><a href="${cp('/files/'+encodeURIComponent(f.ID))}" target="_blank" rel="noopener">${esc(f.FILENAME)}</a></p>`).join('')||empty('첨부파일이 없습니다.')}${manage?'<form id="fileform" class="editor"><label>파일 이름<input name="name" value="community-note.txt" required></label><label>TXT 파일 내용<textarea name="data" required></textarea></label><button>파일 올리기</button></form>':''}</section></article>`;
 }
 async function courses(management=false) {
  if(management)requireAdmin();const rows=await request(wp('/courses'));
  let html=title(management?'강의·신청 관리':'강의 목록')+'<div class="course-grid">'+rows.map(c=>`<article class="course-card">${badge(c['STATE'])}<h3>${esc(c.TITLE)}</h3><p>${esc(c.DESCRIPTION)}</p><small>#${c.ID}</small><div class="actions">${nav('course:'+c.ID,'상세·신청')}${management?`<button data-course-edit="${c.ID}">수정</button><button data-course-delete="${c.ID}" class="danger">삭제</button>`:''}</div></article>`).join('')+'</div>';
  if(management){
   const apps=await request(wp('/admin/applications'));
   html+=`<form id="courseform" class="panel editor"><h3>강의 등록</h3><label>강의명<input name="title" maxlength="160" required></label><label>소개<textarea name="description" maxlength="2000" required></textarea></label><button class="primary">등록</button></form><div class="panel"><h3>수강 신청 검토</h3>${table(['번호','강의·신청자','신청 사유','상태','처리'],apps.map(a=>`<tr><td>#${a.ID}</td><td>${esc(a.TITLE)}<p>${esc(a.USERNAME)}</p></td><td>${esc(a.REASON)}</td><td>${badge(a.STATE)}</td><td>${a.STATE==='PENDING'?`<button data-application="${a.ID}" data-decision="APPROVED">수락</button><button data-application="${a.ID}" data-decision="REJECTED">거절</button>`:'처리 완료'}</td></tr>`))}</div>`;
  }return html;
 }
 async function course(id) {
  const c=await request(wp('/courses/'+id));
  return title('강의 상세')+`<div class="panel">${badge(c['STATE'])}<h2>${esc(c.TITLE)}</h2><p class="body">${esc(c.DESCRIPTION)}</p>${user&&c['STATE']==='OPEN'?`<form id="applyform" class="editor" data-course="${id}"><label>신청 사유<textarea name="reason" maxlength="1000" required></textarea></label><button class="primary">강의 신청</button></form>`:empty(user?'마감된 강의입니다.':'로그인 후 신청할 수 있습니다.')}</div>`;
 }
 async function activity() {
  requireUser();const d=await request(wp('/activity'));
  return title('내 활동')+`<div class="panel"><h3>내 게시글</h3>${d.posts.map(p=>`<p>${nav('post:'+p.ID,p.TITLE)} <span class="meta">${esc(p.VISIBILITY)}</span></p>`).join('')||empty('작성한 게시글이 없습니다.')}</div><div class="panel"><h3>내 댓글</h3>${d.comments.map(c=>`<p>${nav('post:'+c.POST_ID,'게시글 #'+c.POST_ID)} ${esc(c.BODY)}</p>`).join('')||empty('작성한 댓글이 없습니다.')}</div><div class="panel"><h3>내 신고</h3>${table(['번호·대상','신고 사유','상태','처리 내용'],d.reports.map(r=>`<tr><td>#${r.ID} · ${esc(r.TARGET_TYPE)} #${esc(r.TARGET_ID)}</td><td>${esc(r.REASON)}</td><td>${badge(r.STATE)}</td><td>${esc(r.RESOLUTION)}</td></tr>`))}</div><div class="panel"><h3>내 강의 신청</h3>${table(['번호·강의','신청 사유','상태','처리'],d.applications.map(a=>`<tr><td>#${a.ID} · ${esc(a.TITLE)}</td><td>${esc(a.REASON)}</td><td>${badge(a.STATE)}</td><td>${a.STATE==='PENDING'?`<button data-cancel-application="${a.ID}">신청 취소</button>`:''}</td></tr>`))}</div>`;
 }
 async function users() {
  requireAdmin();const rows=await request(wp('/admin/users'));
  return title('사용자 관리')+`<div class="panel">${table(['ID·계정','이메일','권한','계정 상태','변경'],rows.map(u=>`<tr><td>#${u.ID} · ${esc(u.USERNAME)}</td><td>${esc(u.EMAIL)}</td><td>${esc(u.ROLE)}</td><td>${badge(u.STATE)}</td><td>${u.ID===3?'복구용 관리자':`<button data-user="${u.ID}" data-email="${esc(u.EMAIL)}" data-role="${esc(u.ROLE)}" data-state="${esc(u.STATE)}">권한·상태 변경</button>`}</td></tr>`))}<div id="user-editor"></div></div>`;
 }
 async function content() {
  requireAdmin();const posts=await request(wp('/admin/posts')),comments=await request(wp('/admin/comments'));
  return title('게시글·댓글 관리')+`<div class="panel"><h3>전체 게시글</h3>${table(['번호·제목','작성자·공개 범위','관리'],posts.map(p=>`<tr><td>#${p.ID} · ${esc(p.TITLE)}</td><td>${esc(p.USERNAME)} · ${esc(p.VISIBILITY)}</td><td>${nav('post:'+p.ID,'열기')} <button data-delete="${p.ID}" class="danger">삭제</button></td></tr>`))}</div><div class="panel"><h3>전체 댓글</h3>${table(['작성자','내용','관리'],comments.map(c=>`<tr><td>${esc(c.USERNAME)}</td><td>${esc(c.BODY)}</td><td>${nav('post:'+c.POST_ID,'게시글')} <button data-comment-delete="${esc(c.ID)}">삭제</button></td></tr>`))}</div>`;
 }
 async function reports() {
  requireAdmin();const rows=await request(wp('/admin/reports'));
  return title('신고 관리')+`<div class="panel">${table(['번호·신고자','대상','사유','상태','관리'],rows.map(r=>`<tr><td>#${r.ID} · ${esc(r.USERNAME)}</td><td>${esc(r.TARGET_TYPE)} #${esc(r.TARGET_ID)}</td><td>${esc(r.REASON)}<p>${esc(r.RESOLUTION)}</p></td><td>${badge(r.STATE)}</td><td>${r.STATE==='PENDING'?`<button data-report-review="${r.ID}" data-decision="RESOLVED">처리</button><button data-report-review="${r.ID}" data-decision="REJECTED">반려</button>`:''}</td></tr>`))}</div>`;
 }
 async function messages() {
  requireUser();const rows=await request(cp('/messages'));
  return title('쪽지함')+`<div class="panel">${table(['번호','발신·수신','내용'],rows.map(m=>`<tr><td>#${m.ID}</td><td>${m.SENDER_ID} → ${m.RECEIVER_ID}</td><td>${esc(m.BODY)}</td></tr>`))}<form id="msgform" class="editor"><h3>쪽지 보내기</h3><label>받는 회원 ID<input name="receiver" type="number" min="1" value="2" required></label><label>내용<textarea name="body" maxlength="2000" required></textarea></label><button class="primary">보내기</button></form></div>`;
 }
 async function requests(review=false) {
  requireUser();if(review)requireAdmin();
  const d=await request(ap(review?'admin/review-queue':'user/dashboard')),rows=review?d:d.requests;
  return title(review?'서비스 요청 관리':'내 서비스 요청')+(!review?`<div class="notice">${esc(d.notice)}</div>`:'')+`<div class="panel">${table(['번호·제목','내용·신청자','상태','처리'],rows.map(r=>`<tr><td>#${r.ID} · ${esc(r.SUBJECT)}</td><td>${esc(r.BODY||'')} ${esc(r.USERNAME||'')}</td><td>${badge(r.STATE)}</td><td>${review&&r.STATE==='PENDING'?`<button data-request-review="${r.ID}" data-decision="APPROVED">승인</button><button data-request-review="${r.ID}" data-decision="REJECTED">반려</button>`:!review?nav('request:'+r.ID,'상세'):''}</td></tr>`))}</div>`+(!review?'<form id="requestform" class="panel editor"><h3>새 서비스 요청</h3><label>제목<input name="subject" maxlength="160" required></label><label>신청 사유<textarea name="body" maxlength="2000" required></textarea></label><button class="primary">등록</button></form>':'');
 }
 async function requestDetail(id) {requireUser();const d=await request(ap('user/requests/'+id));return title('서비스 요청 상세')+`<div class="panel"><h3>${esc(d.SUBJECT)}</h3><p class="body">${esc(d.BODY)}</p>${badge(d['STATE'])}<div class="actions">${nav('requests','목록')}${d['STATE']==='PENDING'?`<button data-request-cancel="${d.ID}">취소</button>`:''}</div></div>`;}
 async function profile() {
  requireUser();return title('내 정보')+`<form id="profileform" class="panel editor"><h3>${esc(user.username)} · ${esc(user['role'])}</h3><p>회원 ID: ${user['id']}</p><label>이메일<input name="email" type="email" value="${esc(user.email)}" maxlength="140" required></label><button class="primary">저장</button></form>`;
 }
 async function settings() {
  requireAdmin();const d=await request(ap('admin/settings'));
  return title('공지·실습 초기화')+`<form id="settingsform" class="panel editor"><label>커뮤니티 서비스 공지<textarea name="notice" maxlength="500" required>${esc(d.notice)}</textarea></label><button class="primary">저장</button></form><div class="panel"><h3>실습 데이터 복원</h3><p>신고·강의·신청과 기본 계정 권한·정지 상태를 복원합니다. 게시판과 서비스 요청은 따로 초기화할 수 있습니다.</p><div class="actions"><button data-reset="workspace">신고·강의·계정 복원</button><button data-reset="community">게시판·댓글·쪽지 복원</button><button data-reset="access">서비스 요청·공지 복원</button></div></div>`;
 }
 function lab() {
  const cards=[
   ['post','비공개 게시글 IDOR','게시글 번호를 바꿔 다른 사용자의 비공개 글 조회를 비교합니다.','<label>게시글 ID<input name="id" type="number" value="102" required></label>'],
   ['members','관리자 기능 접근','일반 사용자 세션으로 전체 회원 정보 접근을 비교합니다.',''],
   ['report','신고 내역 IDOR','다른 사용자가 접수한 비공개 신고 사유 접근을 비교합니다.','<label>신고 ID<input name="id" type="number" value="602" required></label>'],
   ['approval','강의 수락·거절 권한','일반 사용자 세션으로 관리자 처리 기능 접근을 비교합니다.','<label>신청 ID<input name="id" type="number" value="702" required></label><select name="decision"><option value="APPROVED">수락</option><option value="REJECTED">거절</option></select>'],
   ['search','SQL 인젝션','검색어의 SQL 구문 해석과 파라미터 바인딩 결과를 비교합니다.','<label>검색어<input name="q" value="%\u0027 OR 1=1 -- " required></label>'],
   ['xss','저장형 XSS','같은 댓글을 저장한 뒤 취약 화면과 안전한 문자 표시를 비교합니다.','<label>게시글 ID<input name="id" type="number" value="101" required></label><label>댓글 HTML<textarea name="body" maxlength="2000" required>&lt;img src=x onerror="this[\u0027outerHTML\u0027]=\u0027XSS 실습 실행됨\u0027"&gt;</textarea></label>'],
   ['config','민감한 정보 노출','응답에 포함된 가짜 API 키·토큰과 수정 구현의 차이를 확인합니다.',''],
   ['csrf','CSRF 검증 누락','CSRF 토큰을 보내지 않는 신고 등록 요청을 비교합니다.','<label>게시글 ID<input name="id" type="number" value="101" required></label><label>신고 사유<input name="reason" value="CSRF 검증 실습" required></label>']
  ];
  return title('취약점 실습')+`<div class="lab-banner"><strong>의도적으로 취약한 로컬 실습 메뉴</strong><p>가상 데이터를 사용합니다. 취약 모드의 쓰기 요청은 실제 실습 데이터에 반영됩니다. 관리자의 공지·실습 초기화에서 복원할 수 있습니다.</p></div><div class="lab-controls"><label>실습 구현 <select id="labmode"><option value="vuln" ${labMode==='vuln'?'selected':''}>취약 구현</option><option value="fixed" ${labMode==='fixed'?'selected':''}>수정 구현</option></select></label><span class="meta">현재 계정: ${esc(user?.username||'비회원')} · 권한 비교에는 alice / bob / admin 사용</span></div><div class="lab-grid">${cards.map(([id,t,desc,inputs])=>`<form class="lab-card" data-lab="${id}"><span class="tag">${id.toUpperCase()}</span><h3>${t}</h3><p>${desc}</p>${inputs}<button>요청 실행</button></form>`).join('')}</div><pre id="lab-result" class="lab-result" aria-live="polite">사례를 선택하고 요청을 실행하세요.</pre><div id="lab-preview" class="lab-preview" hidden></div>`;
 }
 async function render() {
  const token=++generation,view=$('#view');view.setAttribute('aria-busy','true');
  document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',b.dataset.page===page));
  try {
   let html;
   if(page==='feed'||page==='login')html=await feed();
   else if(page==='write')html=editor(editing);
   else if(page.startsWith('post:'))html=await detail(Number(page.split(':')[1]));
   else if(page==='courses')html=await courses();
   else if(page==='course-admin')html=await courses(true);
   else if(page.startsWith('course:'))html=await course(Number(page.split(':')[1]));
   else if(page==='activity')html=await activity();
   else if(page==='users')html=await users();
   else if(page==='content')html=await content();
   else if(page==='reports')html=await reports();
   else if(page==='messages')html=await messages();
   else if(page==='profile')html=await profile();
   else if(page==='requests'||page==='queue')html=await requests(page==='queue');
   else if(page.startsWith('request:'))html=await requestDetail(Number(page.split(':')[1]));
   else if(page==='settings')html=await settings();
   else if(page==='lab')html=lab();
   else {page='feed';html=await feed();}
   if(token!==generation)return;view.innerHTML=html;bind();
  } catch(e) {if(token===generation){view.innerHTML=`<div class="panel">${empty(e.message)}<div class="actions">${nav('feed','커뮤니티로')}</div></div>`;bind();}}
  finally {if(token===generation)view.setAttribute('aria-busy','false');}
 }
 const go = p => {page=p;editing=null;render();};
 async function action(button,job) {button.disabled=true;try{await job();}catch(e){toast(e.message);}finally{button.disabled=false;}}
 function form(id,job){const f=$('#'+id);if(f)f.onsubmit=e=>{e.preventDefault();action(f.querySelector('button'),()=>job(Object.fromEntries(new FormData(f)),f));};}
 function bind() {
  $('#view').querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>go(b.dataset.page));
  form('searchform',async fields=>{const token=generation;const rows=await request(cp('/search?q='+encodeURIComponent(fields.q)));if(token!==generation)return;$('#post-list').innerHTML=listPosts(rows);$('#post-list').querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>go(b.dataset.page));});
  form('postform',async fields=>{const d=await post(cp(editing?'/posts/'+editing.ID+'/edit':'/posts'),fields);go('post:'+(d.id??d.updated));});
  form('commentform',async fields=>{await post(cp('/posts/'+page.split(':')[1]+'/comments'),fields);await render();});
  form('fileform',async fields=>{await request(cp('/posts/'+page.split(':')[1]+'/files?name='+encodeURIComponent(fields.name)),{method:'POST',body:fields.data,headers:{'Content-Type':'text/plain;charset=UTF-8'}});await render();});
  form('msgform',async fields=>{await post(cp('/messages'),fields);await render();toast('쪽지를 보냈습니다.');});
  form('applyform',async(fields,f)=>{await post(wp('/courses/'+f.dataset.course+'/apply'),fields);go('activity');toast('강의를 신청했습니다.');});
  form('courseform',async fields=>{await post(wp('/admin/courses'),fields);await render();});
  form('profileform',async fields=>{await post('/api/fixed/profile',fields);await restore();await render();toast('저장했습니다.');});
  form('requestform',async fields=>{await post(ap('user/requests'),fields);await render();});
  form('settingsform',async fields=>{await post(ap('admin/settings'),fields);toast('공지를 저장했습니다.');});
  const clicks=(selector,fn)=>$('#view').querySelectorAll(selector).forEach(b=>b.onclick=()=>action(b,()=>fn(b)));
  clicks('[data-edit]',async b=>{editing=await request(cp('/posts/'+b.dataset.edit));page='write';await render();});
  clicks('[data-delete]',async b=>{if(!confirm('게시글과 연결된 댓글·첨부파일을 삭제할까요?'))return;await post(cp('/posts/'+b.dataset.delete+'/delete'));if(page!=='content')page='feed';await render();});
  clicks('[data-comment-delete]',async b=>{if(!confirm('댓글을 삭제할까요?'))return;await post(cp('/comments/'+encodeURIComponent(b.dataset.commentDelete)+'/delete'));await render();});
  clicks('[data-comment-edit]',async b=>{const body=prompt('댓글 수정',b.dataset.body);if(body===null)return;await post(cp('/comments/'+encodeURIComponent(b.dataset.commentEdit)+'/edit'),{body});await render();});
  clicks('[data-report-type]',async b=>{const reason=prompt('신고 사유를 입력하세요.');if(reason===null)return;await post(wp('/reports'),{targetType:b.dataset.reportType,targetId:b.dataset.reportId,reason});toast('신고를 접수했습니다. 내 활동에서 처리 상태를 확인하세요.');});
  clicks('[data-report-review]',async b=>{const resolution=prompt('처리 내용을 입력하세요.');if(resolution===null)return;await post(wp('/admin/reports/'+b.dataset.reportReview+'/review'),{decision:b.dataset.decision,resolution});await render();});
  clicks('[data-application]',async b=>{await post(wp('/admin/applications/'+b.dataset.application+'/review'),{decision:b.dataset.decision});await render();});
  clicks('[data-cancel-application]',async b=>{await post(wp('/applications/'+b.dataset.cancelApplication+'/cancel'));await render();});
  clicks('[data-course-delete]',async b=>{if(!confirm('강의와 연결된 수강 신청을 삭제할까요?'))return;await post(wp('/admin/courses/'+b.dataset.courseDelete+'/delete'));await render();});
  clicks('[data-course-edit]',async b=>{const c=await request(wp('/courses/'+b.dataset.courseEdit));$('#view').innerHTML=title('강의 수정')+`<form id="courseeditform" class="panel editor"><label>강의명<input name="title" maxlength="160" value="${esc(c.TITLE)}" required></label><label>소개<textarea name="description" maxlength="2000" required>${esc(c.DESCRIPTION)}</textarea></label><label>상태<select name="state"><option value="OPEN" ${c['STATE']==='OPEN'?'selected':''}>신청 가능</option><option value="CLOSED" ${c['STATE']==='CLOSED'?'selected':''}>마감</option></select></label><button class="primary">저장</button></form>`;form('courseeditform',async fields=>{await post(wp('/admin/courses/'+c.ID+'/edit'),fields);go('course-admin');});});
  clicks('[data-user]',async b=>{$('#user-editor').innerHTML=`<form id="usereditform" class="editor"><h3>회원 #${b.dataset.user} 변경</h3><label>이메일<input name="email" type="email" value="${esc(b.dataset.email)}" required></label><label>권한<select name="role">${['USER','MODERATOR','ADMIN'].map(v=>`<option ${b.dataset.role===v?'selected':''}>${v}</option>`).join('')}</select></label><label>계정 상태<select name="state">${['ACTIVE','SUSPENDED'].map(v=>`<option value="${v}" ${b.dataset.state===v?'selected':''}>${state(v)}</option>`).join('')}</select></label><button class="primary">변경 저장</button></form>`;form('usereditform',async fields=>{await post(wp('/admin/users/'+b.dataset.user+'/edit'),fields);await restore();await render();});});
  clicks('[data-request-review]',async b=>{await post(ap('admin/requests/'+b.dataset.requestReview+'/review'),{decision:b.dataset.decision});await render();});
  clicks('[data-request-cancel]',async b=>{await post(ap('user/requests/'+b.dataset.requestCancel+'/cancel'));await render();});
  clicks('[data-reset]',async b=>{if(!confirm('선택한 실습 데이터를 초기화할까요?'))return;await post('/api/'+b.dataset.reset+'/reset');await render();toast('초기화했습니다.');});
  if($('#labmode'))$('#labmode').onchange=e=>{labMode=e.target.value;$('#lab-preview').hidden=true;$('#lab-preview').replaceChildren();$('#lab-result').textContent='구현을 변경했습니다. 요청을 다시 실행하세요.';};
  $('#view').querySelectorAll('[data-lab]').forEach(f=>f.onsubmit=e=>{e.preventDefault();action(f.querySelector('button'),()=>runLab(f));});
 }
 async function runLab(formElement) {
  const fields=Object.fromEntries(new FormData(formElement)), kind=formElement.dataset.lab, mode=labMode, token=generation;
  const c=p=>'/api/community/'+mode+p,w=p=>'/api/workspace/'+mode+p;
  const output=$('#lab-result'),preview=$('#lab-preview');preview.hidden=true;preview.replaceChildren();
  try {
   let data;
   if(kind==='post')data=await request(c('/posts/'+Number(fields.id)));
   if(kind==='members')data=await request(w('/admin/users'));
   if(kind==='report')data=await request(w('/reports/'+Number(fields.id)));
   if(kind==='approval')data=await post(w('/admin/applications/'+Number(fields.id)+'/review'),{decision:fields.decision});
   if(kind==='search')data=await request(c('/search?q='+encodeURIComponent(fields.q)));
   if(kind==='config')data=await request(w('/debug/config'));
   if(kind==='csrf')data=await post(w('/reports'),{targetType:'POST',targetId:fields.id,reason:fields.reason},{noCsrf:true});
   if(kind==='xss'){
    requireUser();await post(c('/posts/'+Number(fields.id)+'/comments'),{body:fields.body});data=await request(c('/posts/'+Number(fields.id)));
    if(token!==generation||mode!==labMode)return;
    preview.hidden=false;
    data.comments.forEach(comment=>{const line=document.createElement('div');line.className='comment';if(mode==='vuln')line.innerHTML=comment.BODY;else line.textContent=comment.BODY;preview.append(line);});
   }
   if(token===generation&&mode===labMode)output.textContent=$('#trace').textContent;
  } catch(e){if(token===generation&&mode===labMode)output.textContent=$('#trace').textContent+'\n'+e.message;}
 }
 document.querySelectorAll('header [data-page],.left [data-page]').forEach(b=>b.onclick=()=>go(b.dataset.page));
 document.querySelectorAll('[data-category]').forEach(b=>b.onclick=()=>{category=b.dataset.category;go('feed');});
 $('#loginform').onsubmit=login;
 $('#logout').onclick=()=>action($('#logout'),async()=>{await post('/api/auth/logout');csrf='';updateUser(null);go('feed');});
 $('#registerform').onsubmit=e=>{e.preventDefault();const fields=Object.fromEntries(new FormData(e.target));action(e.target.querySelector('button'),async()=>{await post('/api/community/register',fields);$('#account').value=fields.username;$('#password').value=fields.password;$('#register').open=false;await login();});};
 restore().then(render);
})();
