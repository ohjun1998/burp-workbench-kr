(() => {
 'use strict';
 const $ = s => document.querySelector(s);
 const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const qp = new URLSearchParams(location.search);
 let permissions=new Set(),permissionLabels={},searchQuery='';
 let user = null, csrf = '', page = qp.get('view') || 'feed', category = 'all', generation = 0, editing = null, labMode = 'vuln';
 const cp = p => '/api/community/fixed' + p, wp = p => '/api/workspace/fixed' + p, ap = p => '/api/access/fixed/' + p;
 const can=p=>permissions.has(p);
 const roleName=r=>({ADMIN:'관리자',USER:'일반 사용자',MODERATOR:'게시판 운영자'}[r]||r);
 const categoryName=c=>({general:'자유 게시판',questions:'질문 게시판',security:'보안 게시판'}[c]||c);
 const visibility=v=>({PUBLIC:'전체 공개',MEMBERS:'회원 전용',PRIVATE:'비공개'}[v]||v);
 const pagePermissions={write:'post:write',activity:'member:read',messages:'message:use',requests:'request:use',request:'request:use',profile:'member:read',users:'user:manage',content:'content:manage',reports:'report:review','course-admin':'course:manage',queue:'request:review',settings:'settings:manage',permissions:'policy:read',audit:'policy:read',moderation:'board:moderate'};
 const pageLabels={feed:'전체 게시글',write:'게시글 작성',post:'게시글 상세',courses:'강의 둘러보기',course:'강의 상세',activity:'내 활동',messages:'쪽지함',requests:'서비스 요청',request:'요청 상세',profile:'내 정보·권한',users:'사용자 관리',content:'게시글·댓글',reports:'신고 관리','course-admin':'강의·신청 관리',queue:'서비스 요청 관리',settings:'공지·실습 설정',permissions:'권한 정책',audit:'접근 기록',moderation:'담당 게시판',lab:'취약점 실습'};
 const state = s => ({PENDING:'검토 대기',APPROVED:'수락',REJECTED:'거절·반려',CANCELLED:'취소',RESOLVED:'처리 완료',OPEN:'신청 가능',CLOSED:'마감',ACTIVE:'활성',SUSPENDED:'정지'}[s] || s);
 const badge = s => `<span class="state" data-state="${esc(s)}">${esc(state(s))}</span>`;
 const title = t => `<div class="toolbar"><h2>${esc(t)}</h2><span class="meta">${user ? esc(user.username) + ' · ' + esc(roleName(user['role'])) : '공개 커뮤니티'}</span></div>`;
 const empty = t => `<p class="empty">${esc(t)}</p>`;
 const nav = (p,t) => `<button type="button" data-page="${p}">${esc(t)}</button>`;
 const table = (headers, rows) => `<p class="table-hint">표를 좌우로 움직여 전체 내용을 확인하세요.</p><div class="table-wrap" tabindex="0" role="region" aria-label="목록 표"><table class="messages"><thead><tr>${headers.map(t=>`<th>${esc(t)}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`;
 function toast(text) { $('#toast').textContent = text; $('#toast').style.display = 'block'; setTimeout(()=>$('#toast').style.display='none',3000); }
 function updateUser(value,granted=[],labels={}) {
  permissions=new Set(granted);permissionLabels=labels;
  user = value ? {id:value.ID ?? value.id, username:value.USERNAME ?? value.username, role:value.ROLE ?? value.role, email:value.EMAIL ?? value.email} : null;
  if(!$('#who'))return;
  $('#who').textContent = user ? user.username + ' · ' + ({ADMIN:'관리자',USER:'일반 사용자',MODERATOR:'게시판 운영자'}[user['role']] || user['role']) : '비회원';
  document.querySelectorAll('[data-permission]').forEach(b=>{b.hidden=!can(b.dataset.permission);});
  document.querySelectorAll('[data-auth-group]').forEach(b=>{b.hidden=!can('member:read');});
  $('#admin-menu').hidden = !['user:manage','content:manage','report:review','course:manage','request:review','settings:manage','policy:read'].some(can);
  $('#moderator-menu').hidden=!can('board:moderate')||can('content:manage');$('#logout').hidden = !user; $('#login-link').hidden = !!user;
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
 async function restore(expected=null) {
  try {const data=await request('/api/access/me');if(expected!==null&&expected!==generation)return;csrf=data.csrf;updateUser(data.user,data.permissions,data.permissionLabels);}
  catch {if(expected!==null&&expected!==generation)return;csrf='';updateUser(null);}
 }
 function requireUser() { if(!user)throw Error('로그인 후 이용하세요. 상단의 로그인·회원가입에서 로그인할 수 있습니다.'); }
 function requirePermission(permission) {requireUser();if(!can(permission))throw Error('이 메뉴를 사용할 권한이 없습니다.');}
 async function login() {
  const d=await post('/api/auth/login',{username:$('#account').value,password:$('#password').value,accountType:$('input[name="accountType"]:checked').value});
  location.assign(d.redirect);
 }
 function initLogin() {
  const types=document.querySelectorAll('input[name="accountType"]');
  function selectType(type,fillAccount=false) {
   types.forEach(input=>{input.checked=input.value===type;});
   const admin=type==='ADMIN';
   $('#login').textContent=admin?'관리자로 로그인':'일반 사용자로 로그인';
   $('#login-help').textContent=admin?'로그인하면 커뮤니티의 사용자 관리 화면으로 이동합니다.':'로그인하면 커뮤니티 게시글 목록으로 이동합니다.';
   if(fillAccount)$('#account').value=admin?'admin':'alice';
   $('#login-error').hidden=true;
  }
  async function submit(job) {
   const buttons=[$('#login'),$('#registerform').querySelector('button')];
   buttons.forEach(button=>{button.disabled=true;});$('#login-error').hidden=true;
   try {await job();}
   catch(e){$('#login-error').textContent=e.message;$('#login-error').hidden=false;}
   finally{buttons.forEach(button=>{button.disabled=false;});}
  }
  types.forEach(input=>{input.onchange=()=>selectType(input.value,true);});
  selectType(qp.get('type')==='admin'?'ADMIN':'USER',true);
  document.querySelectorAll('[data-demo-account]').forEach(b=>{b.onclick=()=>{selectType(b.dataset.type);$('#account').value=b.dataset.demoAccount;$('#password').value='LabOnly!2026';};});
  $('#password-toggle').onclick=()=>{const shown=$('#password').type==='password';$('#password').type=shown?'text':'password';$('#password-toggle').textContent=shown?'숨김':'표시';$('#password-toggle').setAttribute('aria-pressed',String(shown));$('#password-toggle').setAttribute('aria-label',shown?'비밀번호 숨기기':'비밀번호 표시');};
  $('#loginform').onsubmit=e=>{e.preventDefault();submit(login);};
  $('#registerform').onsubmit=e=>{
   e.preventDefault();const fields=Object.fromEntries(new FormData(e.target));
   submit(async()=>{await post('/api/community/register',fields);selectType('USER');$('#account').value=fields.username;$('#password').value=fields.password;await login();});
  };
  request('/api/access/me').then(d=>{
   const admin=d.user.ROLE==='ADMIN';$('#session-user').textContent=d.user.USERNAME+' · '+(admin?'관리자':'일반 사용자')+'로 로그인 중입니다.';
   $('#continue-link').href=admin?'/?view=users':'/?view=feed';$('#session-notice').hidden=false;
  }).catch(()=>{});
 }
 const graphic='<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2l9 4v6c0 5-9 10-9 10S3 17 3 12V6z M8 12l3 3 5-6"/></svg>';
 const stats=items=>'<div class="stat-grid">'+items.map(([label,count])=>`<div class="stat-card"><span class="stat-icon">${graphic}</span><div><small>${esc(label)}</small><strong>${esc(count)}</strong></div></div>`).join('')+'</div>';
 const roleChip=role=>`<span class="role-chip" data-role="${esc(role)}">${esc(roleName(role))}</span>`;
 function listPosts(rows) {
  return rows.map(p=>`<article class="post"><div class="posthead"><span class="tag">${esc(categoryName(p.CATEGORY))}</span><span class="meta">${esc(visibility(p.VISIBILITY))}</span></div><h3><button class="text-link" data-page="post:${p.ID}">${esc(p.TITLE)}</button></h3><div class="post-meta"><span class="avatar">${esc((p.USERNAME||'?').slice(0,1).toUpperCase())}</span><span>${esc(p.USERNAME||'회원')}</span><span class="post-id">#${p.ID}</span></div></article>`).join('') || empty('아직 게시글이 없습니다. 첫 이야기를 남겨 보세요.');
 }
 async function feed() {
  const [rows,courses]=await Promise.all([request(cp(searchQuery?'/search?q='+encodeURIComponent(searchQuery):'/posts')),request(wp('/courses'))]);
  const selected=rows.filter(p=>category==='all'||p.CATEGORY===category);
  const filters=[['all','전체'],['general','자유'],['questions','질문'],['security','보안']];
  return `<section class="hero"><div><span class="eyebrow">COMMUNITY & LEARNING</span><h1>경험을 나누고,<br>함께 성장하는 보안 커뮤니티</h1><p>오늘의 질문이 내일의 실력이 됩니다. 여러분의 이야기를 들려주세요.</p><div class="actions">${can('post:write')?'<button class="primary" data-page="write">이야기 남기기 ↗</button>':'<a class="primary" href="/login">로그인하고 참여하기 ↗</a>'}${nav('courses','강의 둘러보기')}</div></div><div class="hero-graphic">${graphic}</div></section>`+
   stats([['조회 가능한 게시글',rows.length],['전체 공개',rows.filter(p=>p.VISIBILITY==='PUBLIC').length],['회원 전용',rows.filter(p=>p.VISIBILITY==='MEMBERS').length],['신청 가능한 강의',courses.filter(c=>c.STATE==='OPEN').length]])+
   `<div class="feed-shell"><section><div class="toolbar"><h2>${searchQuery?'검색 결과':'커뮤니티 이야기'}</h2><span class="meta">${selected.length}개의 이야기</span></div><div class="feed-filters">${filters.map(([id,label])=>`<button data-category="${id}" class="${category===id?'active':''}">${label}</button>`).join('')}</div><form id="searchform" class="toolbar"><input name="q" value="${esc(searchQuery)}" placeholder="제목으로 게시글 검색" aria-label="게시글 검색" maxlength="100"><button class="secondary">검색</button>${searchQuery?'<button type="button" data-clear-search>초기화</button>':''}</form><div id="post-list">${listPosts(selected)}</div></section><aside class="feed-sidebar"><div class="panel"><h3>관심 있는 이야기 찾기</h3><div class="category-list">${filters['slice'](1).map(([id,label])=>`<button data-category="${id}"><span>${esc(categoryName(id))}</span><strong>${rows.filter(p=>p.CATEGORY===id).length}</strong></button>`).join('')}</div></div><div class="panel"><span class="eyebrow">LEARN BY DOING</span><h3>직접 확인하며 배우세요.</h3><p>같은 요청을 다른 계정으로 비교하고 권한이 어떻게 적용되는지 확인해 보세요.</p><button class="primary" data-page="lab">취약점 실습 둘러보기 ↗</button></div></aside></div>`;
 }
 function editor(p=null) {
  requireUser();editing=p;
  return title(p?'게시글 수정':'게시글 작성')+`<form id="postform" class="panel editor"><label>제목<input name="title" maxlength="200" value="${esc(p?.TITLE||'')}" required></label><div class="row"><label>게시판<select name="category">${[['general','자유 게시판'],['questions','질문 게시판'],['security','보안 게시판']].map(([v,t])=>`<option value="${v}" ${p?.CATEGORY===v?'selected':''}>${t}</option>`).join('')}</select></label><label>공개 범위<select name="visibility">${[['PUBLIC','전체 공개'],['MEMBERS','회원 전용'],['PRIVATE','비공개']].map(([v,t])=>`<option value="${v}" ${p?.VISIBILITY===v?'selected':''}>${t}</option>`).join('')}</select></label></div><label>본문<textarea name="body" maxlength="4000" required>${esc(p?.BODY||'')}</textarea></label><button class="primary">저장</button></form>`;
 }
 async function detail(id) {
  const p=await request(cp('/posts/'+id));
  const manage = !!p.capabilities?.edit;
  const comments=p.comments.map(c=>`<div class="comment"><small>${esc(c.USERNAME)} · #${esc(c.ID)}</small><div class="body">${esc(c.BODY)}</div><div class="comment-tools">${c['canManage']?`<button data-comment-edit="${esc(c.ID)}" data-body="${esc(c.BODY)}">수정</button><button data-comment-delete="${esc(c.ID)}">삭제</button>`:''}${user?`<button data-report-type="COMMENT" data-report-id="${esc(c.ID)}">신고</button>`:''}</div></div>`).join('');
  return title('게시글 상세')+`<article class="post"><div class="posthead"><span class="tag">${esc(categoryName(p.CATEGORY))} · ${esc(visibility(p.VISIBILITY))}</span><span class="meta">${esc(p.USERNAME)} · #${p.ID}</span></div><h2>${esc(p.TITLE)}</h2><div class="body">${esc(p.BODY)}</div><div class="actions">${nav('feed','목록')}${manage?`<button data-edit="${p.ID}">수정</button><button class="danger" data-delete="${p.ID}">삭제</button>`:''}${user?`<button data-report-type="POST" data-report-id="${p.ID}">신고</button>`:''}</div><section class="comments"><h3>댓글 ${p.comments.length}</h3>${comments||empty('아직 댓글이 없습니다.')}${user?'<form id="commentform" class="editor"><label>댓글<textarea name="body" maxlength="2000" required></textarea></label><button class="primary">댓글 등록</button></form>':empty('로그인하면 댓글을 작성할 수 있습니다.')}</section><section class="comments"><h3>첨부파일</h3>${p.files.map(f=>`<p><a href="${cp('/files/'+encodeURIComponent(f.ID))}" target="_blank" rel="noopener">${esc(f.FILENAME)}</a></p>`).join('')||empty('첨부파일이 없습니다.')}${manage?'<form id="fileform" class="editor"><label>파일 이름<input name="name" value="community-note.txt" required></label><label>TXT 파일 내용<textarea name="data" required></textarea></label><button>파일 올리기</button></form>':''}</section></article>`;
 }
 async function courses(management=false) {
  if(management)requirePermission('course:manage');const rows=await request(wp('/courses'));
  let html=title(management?'강의·신청 관리':'강의 둘러보기')+stats([['전체 강의',rows.length],['신청 가능',rows.filter(c=>c.STATE==='OPEN').length],['마감된 강의',rows.filter(c=>c.STATE==='CLOSED').length],['현재 계정',user?roleName(user['role']):'비회원']])+'<div class="course-grid">'+rows.map(c=>`<article class="course-card"><span class="course-number">LOOP CLASS · #${c.ID}</span>${badge(c['STATE'])}<h3>${esc(c.TITLE)}</h3><p>${esc(c.DESCRIPTION)}</p><small>#${c.ID}</small><div class="actions">${nav('course:'+c.ID,'상세·신청')}${management?`<button data-course-edit="${c.ID}">수정</button><button data-course-delete="${c.ID}" class="danger">삭제</button>`:''}</div></article>`).join('')+'</div>';
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
  requirePermission('user:manage');const rows=await request(wp('/admin/users'));
  return title('사용자 관리')+stats([['전체 회원',rows.length],['활성 계정',rows.filter(u=>u.STATE==='ACTIVE').length],['관리자',rows.filter(u=>u.ROLE==='ADMIN').length],['정지 계정',rows.filter(u=>u.STATE==='SUSPENDED').length]])+`<div class="panel">${table(['ID·계정','이메일','권한','계정 상태','변경'],rows.map(u=>`<tr><td><div class="user-cell"><span class="avatar">${esc(u.USERNAME.slice(0,1).toUpperCase())}</span><div><strong>${esc(u.USERNAME)}</strong><small>MEMBER #${u.ID}</small></div></div></td><td>${esc(u.EMAIL)}</td><td>${roleChip(u.ROLE)}</td><td>${badge(u.STATE)}</td><td>${u.ID===3?'복구용 관리자':`<button data-user="${u.ID}" data-email="${esc(u.EMAIL)}" data-role="${esc(u.ROLE)}" data-state="${esc(u.STATE)}">권한·상태 변경</button>`}</td></tr>`))}<div id="user-editor"></div></div>`;
 }
 async function content() {
  requirePermission('content:manage');const posts=await request(wp('/admin/posts')),comments=await request(wp('/admin/comments'));
  return title('게시글·댓글 관리')+`<div class="panel"><h3>전체 게시글</h3>${table(['번호·제목','작성자·공개 범위','관리'],posts.map(p=>`<tr><td>#${p.ID} · ${esc(p.TITLE)}</td><td>${esc(p.USERNAME)} · ${esc(p.VISIBILITY)}</td><td>${nav('post:'+p.ID,'열기')} <button data-delete="${p.ID}" class="danger">삭제</button></td></tr>`))}</div><div class="panel"><h3>전체 댓글</h3>${table(['작성자','내용','관리'],comments.map(c=>`<tr><td>${esc(c.USERNAME)}</td><td>${esc(c.BODY)}</td><td>${nav('post:'+c.POST_ID,'게시글')} <button data-comment-delete="${esc(c.ID)}">삭제</button></td></tr>`))}</div>`;
 }
 async function reports() {
  requirePermission('report:review');const rows=await request(wp('/admin/reports'));
  return title('신고 관리')+`<div class="panel">${table(['번호·신고자','대상','사유','상태','관리'],rows.map(r=>`<tr><td>#${r.ID} · ${esc(r.USERNAME)}</td><td>${esc(r.TARGET_TYPE)} #${esc(r.TARGET_ID)}</td><td>${esc(r.REASON)}<p>${esc(r.RESOLUTION)}</p></td><td>${badge(r.STATE)}</td><td>${r.STATE==='PENDING'?`<button data-report-review="${r.ID}" data-decision="RESOLVED">처리</button><button data-report-review="${r.ID}" data-decision="REJECTED">반려</button>`:''}</td></tr>`))}</div>`;
 }
 async function messages() {
  requireUser();const rows=await request(cp('/messages'));
  return title('쪽지함')+`<div class="panel">${table(['번호','발신·수신','내용'],rows.map(m=>`<tr><td>#${m.ID}</td><td>${m.SENDER_ID} → ${m.RECEIVER_ID}</td><td>${esc(m.BODY)}</td></tr>`))}<form id="msgform" class="editor"><h3>쪽지 보내기</h3><label>받는 회원 ID<input name="receiver" type="number" min="1" value="2" required></label><label>내용<textarea name="body" maxlength="2000" required></textarea></label><button class="primary">보내기</button></form></div>`;
 }
 async function requests(review=false) {
  requireUser();if(review)requirePermission('request:review');
  const d=await request(ap(review?'admin/review-queue':'user/dashboard')),rows=review?d:d.requests;
  return title(review?'서비스 요청 관리':'내 서비스 요청')+(!review?`<div class="notice">${esc(d.notice)}</div>`:'')+`<div class="panel">${table(['번호·제목','내용·신청자','상태','처리'],rows.map(r=>`<tr><td>#${r.ID} · ${esc(r.SUBJECT)}</td><td>${esc(r.BODY||'')} ${esc(r.USERNAME||'')}</td><td>${badge(r.STATE)}</td><td>${review&&r.STATE==='PENDING'?`<button data-request-review="${r.ID}" data-decision="APPROVED">승인</button><button data-request-review="${r.ID}" data-decision="REJECTED">반려</button>`:!review?nav('request:'+r.ID,'상세'):''}</td></tr>`))}</div>`+(!review?'<form id="requestform" class="panel editor"><h3>새 서비스 요청</h3><label>제목<input name="subject" maxlength="160" required></label><label>신청 사유<textarea name="body" maxlength="2000" required></textarea></label><button class="primary">등록</button></form>':'');
 }
 async function requestDetail(id) {requireUser();const d=await request(ap('user/requests/'+id));return title('서비스 요청 상세')+`<div class="panel"><h3>${esc(d.SUBJECT)}</h3><p class="body">${esc(d.BODY)}</p>${badge(d['STATE'])}<div class="actions">${nav('requests','목록')}${d['STATE']==='PENDING'?`<button data-request-cancel="${d.ID}">취소</button>`:''}</div></div>`;}
 async function profile() {
  requireUser();return title('내 정보·권한')+`<form id="profileform" class="panel editor"><h3>${esc(user.username)} ${roleChip(user['role'])}</h3><p class="small">회원 ID #${user['id']}</p><label>이메일<input name="email" type="email" value="${esc(user.email)}" maxlength="140" required></label><button class="primary">변경 사항 저장</button></form><section class="panel"><h3>내가 할 수 있는 작업</h3><p class="small">계정에 부여된 역할을 기준으로 표시합니다. 게시글·쪽지 등은 접근이 허용된 데이터만 이용할 수 있습니다.</p><ul class="permission-list">${Array['from'](permissions).map(p=>`<li>${esc(permissionLabels[p]||p)}</li>`).join('')}</ul></section>`;
 }
 async function policyPage() {
  requirePermission('policy:read');const data=await request(ap('admin/permissions'));
  return title('권한 정책')+`<p class="page-description">역할별로 사용할 수 있는 기능을 확인하세요.</p><div class="notice">작성자와 담당 게시판, 신청 상태에 따라 같은 기능도 접근 범위가 달라집니다. 역할 변경은 다음 요청부터 적용됩니다.</div><section class="panel policy-panel">${table(['기능','일반 사용자','게시판 운영자','관리자'],Object['entries'](data['labels']).map(([id,label])=>`<tr><td>${esc(label)}</td>${data.roles.map(r=>`<td><span class="${r.permissions.includes(id)?'permission-yes':'permission-no'}" aria-label="${r.permissions.includes(id)?'허용':'권한 없음'}">${r.permissions.includes(id)?'✓':'—'}</span></td>`).join('')}</tr>`))}</section>`;
 }
 async function auditPage() {
  requirePermission('policy:read');const data=await request(ap('admin/audit'));
  return title('접근 기록')+`<p class="page-description">권한이 거부된 요청과 완료된 변경 작업을 확인합니다.</p><div class="notice">최근 ${data.capacity}건을 표시하며 서버를 다시 시작하면 기록이 초기화됩니다. 비밀번호·쿠키·요청 본문은 기록하지 않습니다.</div><section class="panel">${table(['시각','계정','작업','결과'],data.items.map(r=>`<tr><td>${esc(new Date(r.time).toLocaleString('ko-KR'))}</td><td>${esc(r.username||'비회원')}<p>${esc(roleName(r.role))}</p></td><td>${esc(r.method)} ${esc(r.path)}</td><td><span class="audit-status ${r.outcome==='ALLOWED'?'allowed':'denied'}">${r.outcome==='DENIED'?'거부':r.outcome==='NOT_FOUND'?'조회 불가':'완료'} · ${esc(r.status)}</span></td></tr>`))}</section>`;
 }
 async function moderation() {
  requirePermission('board:moderate');const rows=await request(wp('/moderation/posts'));
  return title('담당 게시판')+`<p class="page-description">보안 게시판의 공개·회원 전용 게시글을 관리합니다.</p>`+listPosts(rows);
 }
 async function settings() {
  requirePermission('settings:manage');const d=await request(ap('admin/settings'));
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
  view.innerHTML='<div class="loading">화면을 불러오는 중입니다.</div>';
  await restore(token);if(token!==generation)return;
  const key=page.split(':')[0];$('#current-title').textContent=pageLabels[key]||'커뮤니티';$('#current-section').textContent=['users','content','reports','course-admin','queue','settings','permissions','audit'].includes(key)?'커뮤니티 관리':key==='lab'?'보안 실습':key==='moderation'?'게시판 운영':'커뮤니티';
  document.querySelectorAll('[data-page]').forEach(b=>{const active=b.dataset.page===page;b.classList.toggle('active',active);if(active)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');});
  try {
   if(pagePermissions[key])requirePermission(pagePermissions[key]);
   let html;
   if(page==='feed')html=await feed();
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
   else if(page==='permissions')html=await policyPage();
   else if(page==='audit')html=await auditPage();
   else if(page==='moderation')html=await moderation();
   else {page='feed';html=await feed();}
   if(token!==generation)return;view.innerHTML=html;bind();
  } catch(e) {if(token===generation){view.innerHTML=`<div class="panel">${empty(e.message)}<div class="actions">${!user?'<a class="primary" href="/login">로그인하기</a>':''}${nav('feed','커뮤니티로')}</div></div>`;bind();}}
  finally {if(token===generation)view.setAttribute('aria-busy','false');}
 }
 const go = p => {page=p;editing=null;history.pushState({},'', '/?view='+encodeURIComponent(p));closeNavigation();render();window.scrollTo({top:0,behavior:'instant'});};
 async function action(button,job) {button.disabled=true;try{await job();}catch(e){if(e.status===401||e.status===403)await render();toast(e.message);}finally{button.disabled=false;}}
 function form(id,job){const f=$('#'+id);if(f)f.onsubmit=e=>{e.preventDefault();action(f.querySelector('button'),()=>job(Object.fromEntries(new FormData(f)),f));};}
 function bind() {
  $('#view').querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>go(b.dataset.page));
  form('searchform',async fields=>{searchQuery=fields.q.trim();go('feed');});
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
  clicks('[data-category]',async b=>{category=b.dataset.category;go('feed');});
  clicks('[data-clear-search]',async()=>{searchQuery='';$('#global-search').value='';go('feed');});
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
 function closeNavigation() {
  document.body.classList.remove('nav-open');$('#nav-backdrop').hidden=true;$('#app-content').inert=false;$('.top').inert=false;
  const mobile=window.matchMedia('(max-width:1024px)').matches;
  $('#main-navigation').inert=mobile||document.body.classList.contains('nav-hidden');
  $('#menu-toggle').setAttribute('aria-expanded',String(!mobile&&!document.body.classList.contains('nav-hidden')));
 }
 function initNavigation() {
  closeNavigation();$('#menu-toggle').onclick=()=>{
   const mobile=window.matchMedia('(max-width:1024px)').matches;
   if(mobile){document.body.classList.add('nav-open');$('#nav-backdrop').hidden=false;$('#main-navigation').inert=false;$('#app-content').inert=true;$('.top').inert=true;$('#menu-toggle').setAttribute('aria-expanded','true');$('#nav-close').focus();}
   else{document.body.classList.toggle('nav-hidden');closeNavigation();}
  };
  $('#nav-backdrop').onclick=closeNavigation;$('#nav-close').onclick=()=>{closeNavigation();$('#menu-toggle').focus();};
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&document.body.classList.contains('nav-open')){closeNavigation();$('#menu-toggle').focus();}});
  window.addEventListener('resize',closeNavigation);
  window.addEventListener('popstate',()=>{page=new URLSearchParams(location.search).get('view')||'feed';editing=null;closeNavigation();render();});
  $('#global-searchform').onsubmit=e=>{e.preventDefault();searchQuery=$('#global-search').value.trim();category='all';go('feed');};
 }
 if(document.body.dataset.screen==='login'){initLogin();return;}
 if(page==='login'){location.replace('/login');return;}
 document.querySelectorAll('header [data-page],.left [data-page]').forEach(b=>b.onclick=()=>go(b.dataset.page));
 document.querySelectorAll('[data-category]').forEach(b=>b.onclick=()=>{category=b.dataset.category;go('feed');});
 $('#logout').onclick=()=>action($('#logout'),async()=>{await post('/api/auth/logout');csrf='';updateUser(null);location.assign('/login');});
 initNavigation();render();
})();
