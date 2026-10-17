(() => {
  'use strict';
  const $ = s => document.querySelector(s), area = document.body.dataset.area, admin = area === 'admin';
  const esc = x => String(x ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const stateName = s => ({PENDING:'검토 대기',APPROVED:'승인',REJECTED:'반려',CANCELLED:'취소'}[s] || s);
  let csrf = '', mode = 'fixed', current = admin ? 'queue' : 'dashboard', generation = 0;
  const message = text => { $('#message').textContent = text; };
  async function request(path, options = {}) {
    const headers = {...options.headers};
    if (options.body instanceof URLSearchParams) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
    if (options.method === 'POST') headers['X-CSRF-Token'] = csrf;
    const response = await fetch(path, {...options, headers, credentials:'same-origin', cache:'no-store'});
    const text = await response.text();
    if ($('#trace')) $('#trace').textContent = `${options.method || 'GET'} ${path}\nHTTP ${response.status}\n${text}`;
    let data; try { data = JSON.parse(text); } catch { data = {}; }
    if (!response.ok) {
      if (response.status === 401 && !document.body.dataset.login) {
        $('#view').replaceChildren(); location.assign('/' + area + '/login');
      }
      throw new Error(data.error || 'HTTP ' + response.status);
    }
    return data;
  }
  const api = suffix => '/api/access/' + mode + '/' + suffix;
  const post = (path, fields = {}) => request(path, {method:'POST', body:new URLSearchParams(fields)});
  if (document.body.dataset.login) {
    $('#username').value = admin ? 'admin' : 'alice';
    $('#sample-accounts').textContent = admin ? 'admin · 전체 관리자' : 'alice / bob · 일반 사용자';
    $('#login-features').innerHTML = (admin ? ['전체 회원 조회', '서비스 요청 승인·반려', '서비스 공지 설정'] : ['내 서비스 요청 조회', '새 요청 작성·취소', '공지 및 처리 상태 확인']).map(x => '<p>' + esc(x) + '</p>').join('');
    $('#login-form').addEventListener('submit', async event => {
      event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
      try {
        const data = await post('/api/auth/login', Object.fromEntries(new FormData(event.target)));
        const role = data.user.ROLE ?? data.user.role;
        if (role !== (admin ? 'ADMIN' : 'USER')) throw new Error('이 계정은 해당 센터에 접근할 수 없습니다. 역할에 맞는 로그인 화면을 선택하세요.');
        location.assign('/' + area);
      } catch (error) { message(error.message); } finally { button.disabled = false; }
    });
    return;
  }
  const menus = admin ? [['queue','요청 검토'],['members','회원 관리'],['settings','서비스 설정']] : [['dashboard','내 요청'],['create','새 요청'],['profile','내 프로필']];
  $('#menu').innerHTML = menus.map(([id,label]) => `<button data-page="${id}">${label}</button>`).join('');
  let user;
  function table(rows, review) {
    return `<div class="table-wrap"><table><thead><tr><th>요청</th>${review?'<th>신청자</th>':''}<th>제목</th><th>상태</th><th>처리</th></tr></thead><tbody>` + rows.map(row => `<tr><td>#${row.ID}</td>${review?`<td>${esc(row.USERNAME)}</td>`:''}<td>${esc(row.SUBJECT)}${review?`<p class="meta">${esc(row.BODY)}</p>`:''}</td><td><span class="state">${esc(stateName(row.STATE))}</span></td><td>${review ? row.STATE==='PENDING'?`<button data-review="${row.ID}" data-decision="APPROVED">승인</button><button data-review="${row.ID}" data-decision="REJECTED">반려</button>`:'처리 완료' : `<button data-detail="${row.ID}">상세 보기</button>`}</td></tr>`).join('') + '</tbody></table></div>' + (rows.length?'':'<p class="empty">아직 요청이 없습니다.</p>');
  }
  async function render() {
    const token = ++generation, view = $('#view'); view.setAttribute('aria-busy', 'true'); message('');
    $('#menu').querySelectorAll('button').forEach(b => b.classList.toggle('active', b.dataset.page === current));
    $('#page-title').textContent = menus.find(([id]) => id === current)?.[1] || '요청 상세';
    try {
      let html = '';
      if (current === 'dashboard') {
        const data = await request(api('user/dashboard')), rows = data.requests;
        html = `<div class="notice">${esc(data.notice)}</div><div class="cards">${[['전체 요청',rows.length],['검토 대기',rows.filter(x=>x.STATE==='PENDING').length],['승인',rows.filter(x=>x.STATE==='APPROVED').length]].map(([label,count])=>`<div class="card"><h3>${label}</h3><span class="stat">${count}</span></div>`).join('')}</div><div class="card">${table(rows,false)}</div>`;
      } else if (current === 'queue') {
        const rows = await request(api('admin/review-queue'));
        html = `<div class="card"><h2>서비스 요청 검토</h2><p class="meta">전체 사용자의 신청 사유를 확인하고 처리합니다.</p>${table(rows,true)}</div>`;
      } else if (current === 'members') {
        const rows = await request(api('admin/members'));
        html = `<div class="card"><h2>전체 회원 ${rows.length}명</h2><div class="table-wrap"><table><thead><tr><th>ID</th><th>계정</th><th>역할</th><th>이메일</th></tr></thead><tbody>${rows.map(x=>`<tr><td>${x.ID}</td><td>${esc(x.USERNAME)}</td><td>${esc(x.ROLE)}</td><td>${esc(x.EMAIL)}</td></tr>`).join('')}</tbody></table></div></div>`;
      } else if (current === 'settings') {
        const data = await request(api('admin/settings'));
        html = `<div class="card"><h2>서비스 공지</h2><form id="settings-form" class="form-grid"><label>사용자 센터에 표시할 공지<textarea name="notice" maxlength="500" required>${esc(data.notice)}</textarea></label><div><button class="primary">공지 저장</button></div></form><hr><h3>실습 초기화</h3><p class="meta">요청 501·502·503과 공지를 초기 상태로 되돌립니다.</p><button id="reset">실습 데이터 초기화</button></div>`;
      } else if (current === 'create') {
        html = '<div class="card"><h2>서비스 요청 작성</h2><form id="create-form" class="form-grid"><label>제목<input name="subject" maxlength="160" required></label><label>신청 사유<textarea name="body" maxlength="2000" required></textarea></label><div><button class="primary">요청 등록</button></div></form></div>';
      } else if (current === 'profile') {
        html = `<div class="card"><h2>${esc(user.USERNAME)}</h2><p>사용자 ID: ${user.ID}</p><p>권한: ${esc(user.ROLE)}</p><p>이메일: ${esc(user.EMAIL)}</p></div>`;
      } else if (current.startsWith('detail:')) {
        const data = await request(api('user/requests/' + current.split(':')[1]));
        html = `<div class="card"><span class="state">${esc(stateName(data.STATE))}</span><h2>${esc(data.SUBJECT)}</h2><p class="meta">#${data.ID} · ${esc(data.USERNAME)}</p><p class="detail-body">${esc(data.BODY)}</p><div class="actions"><button data-page="dashboard">내 요청으로</button>${data.STATE==='PENDING'?`<button data-cancel="${data.ID}">요청 취소</button>`:''}</div></div>`;
      }
      if (token !== generation) return;
      view.innerHTML = html; bind();
    } catch (error) { if (token === generation) {view.replaceChildren(); message(error.message);} }
    finally { if(token===generation) view.setAttribute('aria-busy','false'); }
  }
  function bind() {
    $('#view').querySelectorAll('[data-page]').forEach(b => b.onclick = () => { current=b.dataset.page; render(); });
    $('#view').querySelectorAll('[data-detail]').forEach(b => b.onclick = () => { current='detail:'+b.dataset.detail; render(); });
    const action = (button, job) => {button.disabled=true; job().catch(e=>message(e.message)).finally(()=>{button.disabled=false;});};
    $('#view').querySelectorAll('[data-review]').forEach(b => b.onclick = () => action(b, async()=>{await post(api(`admin/requests/${b.dataset.review}/review`),{decision:b.dataset.decision});await render();}));
    $('#view').querySelectorAll('[data-cancel]').forEach(b => b.onclick = () => action(b, async()=>{await post(api(`user/requests/${b.dataset.cancel}/cancel`));current='dashboard';await render();}));
    for (const [id,suffix,next] of [['create-form','user/requests','dashboard'],['settings-form','admin/settings','settings']]) {
      const form = $('#'+id); if(form) form.onsubmit = e => {e.preventDefault();action(form.querySelector('button'),async()=>{await post(api(suffix),Object.fromEntries(new FormData(form)));current=next;await render();message('저장했습니다.');});};
    }
    if($('#reset')) $('#reset').onclick = e => {if(confirm('요청과 공지를 초기화할까요?'))action(e.target,async()=>{await post('/api/access/reset');await render();message('초기화했습니다.');});};
  }
  $('#menu').querySelectorAll('[data-page]').forEach(b => b.onclick = () => {current=b.dataset.page;render();});
  $('#mode').onchange = e => {mode=e.target.value;render();}; $('#refresh').onclick=render;
  $('#logout').onclick = async () => {try{await post('/api/auth/logout');location.assign('/'+area+'/login');}catch(e){message(e.message);}};
  request('/api/access/me').then(data => {
    csrf=data.csrf;user=data.user;
    if(user.ROLE !== (admin?'ADMIN':'USER')) { $('#view').replaceChildren();message('역할이 변경되었습니다. 해당 계정의 센터로 다시 로그인하세요.');$('#menu').replaceChildren();return; }
    $('#identity').textContent=user.USERNAME;$('#role-badge').textContent=admin?'ADMIN · 전체 관리자':'USER · 일반 사용자';render();
  }).catch(e=>message(e.message));
})();
