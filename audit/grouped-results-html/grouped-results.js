'use strict';
const PRESENTATION=JSON.parse(document.getElementById('presentation-data').textContent);
const CAPTURES=JSON.parse(document.getElementById('native-captures').textContent);
const GROUPED_KEYS=new Set(['traffic','mapper','auth','js','environment','response','activity','bounty','scope','dashboard']);
const GROUP_ORDER=['business','account','pages','docs','assets','ops','inputs','control','common','other'];
const FEATURE_PRESENTATION=new Map(PRESENTATION.mapperGroups.flatMap(g=>g.entries.map(e=>[e.id,{...e,groupKey:g.key,groupLabel:g.label}])));
const GROUP_UI={filters:{},expanded:{},originals:{},mode:'results',zoom:false};
const BASE_TABLE=table,BASE_SELECTED=selected,BASE_RENDER=render,BASE_FILTERED=filtered;
function routeLabel(url){return PRESENTATION.labels[url]||{label:'경로 관측',groupKey:'other',groupLabel:'기타 관측'};}
function groupContext(key){return key+'|'+(key==='dashboard'?UI.metric:key==='scope'?UI.category:key==='js'?UI.jsSource:'');}
function sourcePosition(key,row,fallback){
  const sources={traffic:D.traffic,mapper:D.mapper.metadata.packets,auth:D.auth,js:D.js[UI.jsSource]?.endpoints,environment:D.paths,response:D.response,activity:D.activity.data.endpoints,bounty:D.business.steps,scope:D.scope.findings,dashboard:UI.metric==='paths'?D.scope.pages:D.scope.findings};
  const index=(sources[key]||[]).indexOf(row);return index<0?fallback:index;
}
function rowPresentation(key,row,index=0){
  const url=String(row?.url||'');let result={...routeLabel(url),url,functionKey:(row?.method||'')+'|'+url};
  if(key==='mapper'){
    const feature=FEATURE_PRESENTATION.get(row.primary);
    if(feature)result={...result,...feature,functionKey:row.primary};
  }else if((key==='scope'||key==='dashboard')&&row.category){
    if(['API','INVENTORY'].includes(row.category))result.functionKey=row.category+'|'+row.title+'|'+url;
    else result={...result,groupKey:'finding:'+row.category,groupLabel:CATEGORY[row.category]||row.category,label:row.title,functionKey:row.category+'|'+row.title};
  }else if(key==='response')result={...result,groupKey:'response:'+row.rule,groupLabel:row.rule,functionKey:row.rule+'|'+url};
  return {...result,rowId:key+'|'+(key==='js'?UI.jsSource+'|':'')+sourcePosition(key,row,index)};
}
function groupMatches(key,row,index){
  const chosen=GROUP_UI.filters[groupContext(key)];if(!chosen)return true;
  const item=rowPresentation(key,row,index);
  return (!chosen.group||chosen.group===item.groupKey)&&(!chosen.leaf||chosen.leaf===item.functionKey);
}
filtered=function(rows){
  if(!UI.q||!GROUPED_KEYS.has(UI.page))return BASE_FILTERED(rows);
  const query=UI.q.toLocaleLowerCase();
  return rows.filter((row,index)=>(JSON.stringify(row)+' '+rowPresentation(UI.page,row,index).label+' '+rowPresentation(UI.page,row,index).groupLabel).toLocaleLowerCase().includes(query));
};
selected=function(key,rows,predicate){
  if(!GROUPED_KEYS.has(key))return BASE_SELECTED(key,rows,predicate);
  const base=BASE_SELECTED(key,rows,predicate);
  if(base.row&&groupMatches(key,base.row,base.index))return base;
  const index=rows.findIndex((row,i)=>groupMatches(key,row,i));
  if(index<0&&rows.length){
    delete GROUP_UI.filters[groupContext(key)];
    return BASE_SELECTED(key,rows,predicate);
  }
  if(index>=0)UI.sel[key]=index;
  return {row:index>=0?rows[index]:undefined,index};
};
function groupButton(key,group,label,n,leaf){
  const context=groupContext(key),chosen=GROUP_UI.filters[context];
  const active=leaf?chosen?.leaf===leaf:group==='all'?!chosen:chosen?.group===group&&!chosen?.leaf;
  return '<button type="button" class="result-choice'+(active?' active':'')+'" data-result-owner="'+E(key)+'" '+(leaf?'data-result-leaf="'+E(leaf)+'"':'data-result-group="'+E(group)+'"')+' data-result-count="'+n+'"'+(leaf?' data-result-parent="'+E(group)+'"':'')+' aria-pressed="'+active+'"><span>'+E(label)+'</span><b>'+count(n)+'건</b></button>';
}
function groupedNavigation(key,rows){
  const context=groupContext(key),groups=new Map();
  rows.forEach((row,index)=>{
    const item=rowPresentation(key,row,index);
    if(!groups.has(item.groupKey))groups.set(item.groupKey,{label:item.groupLabel,key:item.groupKey,items:[]});
    groups.get(item.groupKey).items.push(item);
  });
  const ordered=[...groups.values()].sort((a,b)=>{
    const ai=GROUP_ORDER.indexOf(a.key),bi=GROUP_ORDER.indexOf(b.key);
    return (ai<0?99:ai)-(bi<0?99:bi);
  });
  let html=groupButton(key,'all','전체 관측',rows.length);
  for(const group of ordered){
    const stateKey=context+'|'+group.key;
    const open=GROUP_UI.expanded[stateKey]??(['business','account'].includes(group.key)||groups.size===1);
    const functions=new Map();for(const item of group.items){if(!functions.has(item.functionKey))functions.set(item.functionKey,{label:item.label,rows:0});functions.get(item.functionKey).rows++;}
    const duplicateNames=new Map();for(const item of functions.values())duplicateNames.set(item.label,(duplicateNames.get(item.label)||0)+1);
    const summaryCount=key==='mapper'?count(functions.size)+'개':count(group.items.length)+'건';
    html+='<details class="result-folder" data-result-folder="'+E(stateKey)+'"'+(open?' open':'')+'><summary><span>'+E(group.label)+'</span><b>'+summaryCount+'</b></summary>';
    html+=groupButton(key,group.key,'이 그룹 모두 보기',group.items.length);
    let ordinal=0;for(const [functionKey,entry] of functions){
      ordinal++;const label=entry.label+(duplicateNames.get(entry.label)>1?' · '+String(ordinal).padStart(2,'0'):'');
      html+=groupButton(key,group.key,label,entry.rows,functionKey);
    }
    html+='</details>';
  }
  return '<aside class="result-navigation" aria-label="'+E(key)+' 결과 그룹" data-scroll="groups-'+E(context)+'">'+html+'</aside>';
}
table=function(key,rows,columns,chosen=null){
  if(!GROUPED_KEYS.has(key))return BASE_TABLE(key,rows,columns,chosen);
  const aliases=columns.map(column=>{
    if(!['URL','경로','관측 경로','요청 경로'].includes(column.label))return column;
    return {label:'기능·대상',value:(row,index)=>key==='mapper'?rowPresentation(key,row,index).label:routeLabel(String(row.url||'')).label};
  });
  const visible=rows.map((row,index)=>groupMatches(key,row,index));const shown=visible.filter(Boolean).length;
  let list='<div class="table-wrap" data-scroll="'+E(key)+'"><table class="data-table"><thead><tr>'+aliases.map(c=>'<th>'+E(c.label)+'</th>').join('')+'</tr></thead><tbody>';
  rows.forEach((row,index)=>{
    list+='<tr tabindex="0" data-row="'+E(key)+'" data-index="'+index+'"'+(!visible[index]?' hidden':'')+' class="'+(chosen===index?'selected':'')+'" aria-selected="'+(chosen===index)+'">';
    for(const column of aliases)list+='<td>'+(column.html?column.html(row,index):E(column.value?column.value(row,index):row[column.key]))+'</td>';
    list+='</tr>';
  });
  list+='</tbody></table>'+(!shown?empty('선택한 조건의 저장 결과가 없습니다.'):'')+'</div>';
  const context=groupContext(key),row=chosen!==null&&chosen>=0?rows[chosen]:null;
  let original='';if(row){const item=rowPresentation(key,row,chosen);original=kv([['표시명',item.label],['원본 URL',item.url],['저장 데이터 행',sourcePosition(key,row,chosen)+1],['관측·원본 ID',row.originalId||row.id||row.index||row.step||item.rowId],...(key==='mapper'?[['기능 ID',row.primary],['원래 기능명',FEATURE_PRESENTATION.get(row.primary)?.originalName||row.entity]]:[])]);}
  return '<div class="grouped-result" data-result-owner="'+E(key)+'">'+groupedNavigation(key,rows)+'<div class="result-table-area"><div class="result-table-status">표시 <strong>'+count(shown)+'</strong> / 전체 '+count(rows.length)+'건</div>'+list+'<details class="result-original" data-result-original="'+E(key)+'" data-result-original-context="'+E(context)+'"'+(GROUP_UI.originals[context]?' open':'')+'><summary>원본 정보 보기</summary><div class="card-body">'+(original||empty('행을 선택하세요.'))+'</div></details></div></div>';
};
mapperView=function(){
  const rows=filtered(D.mapper.metadata.packets),s=selected('mapper',rows),r=s.row;
  return line(['<strong>관측 '+D.mapper.packets+'건</strong>','<span>원래 기능 '+D.mapper.metadata.features.length+'개</span>','<span class="muted">저장된 주 기능 분류 · 표시명만 변경</span>'])+'<div class="master-detail">'+card('기능별 요청',table('mapper',rows,[{label:'메서드',key:'method'},{label:'기능',value:p=>rowPresentation('mapper',p).label},{label:'HTTP',html:p=>status(p.status)},{label:'동작',key:'operation'}],s.index),'table-card')+card('선택한 관측','<div class="card-body">'+(r?kv([['기능',rowPresentation('mapper',r).label],['HTTP',r.status],['메서드',r.method],['분류 근거',r.confidence],['요청 크기',r.requestBytes+' B'],['응답 크기',r.responseBytes+' B'],['점검 상태',FEATURE_PRESENTATION.get(r.primary)?.state||'미점검']]):empty())+'</div>')+'</div>';
};
const BASE_JS_VIEW=jsView;
jsView=function(){
  const source=BASE_JS_VIEW();
  return source.replace(/(<option value="(\d+)"[^>]*>)[\s\S]*?(<\/option>)/g,(all,start,index,end)=>{
    const item=D.js[Number(index)];return item?start+(Number(index)+1)+'. '+E(routeLabel(item.url).label)+' · '+item.endpoints.length+'건'+end:all;
  });
};
function nativeView(){
  const theme=document.body.dataset.theme==='dark'?'dark':'light';
  const picture=CAPTURES[theme]?.[UI.page];
  return '<div class="capture-note"><strong>Workbench Simple 1.4.22 · 실제 확장 화면</strong><span>화면 검증용 합성 데이터로 촬영한 Linux Swing 캡처입니다. 은행 저장 결과와는 별개입니다.</span><button type="button" data-native-zoom="1">'+(GROUP_UI.zoom?'화면에 맞추기':'원본 크기로 보기')+'</button></div><div class="capture-stage'+(GROUP_UI.zoom?' zoomed':'')+'" data-scroll="native-'+UI.page+'">'+(picture?'<img src="'+picture.dataUrl+'" width="'+picture.width+'" height="'+picture.height+'" alt="'+E(PAGES.find(p=>p[0]===UI.page)[1])+' · '+theme+' 실제 확장 캡처">':empty('이 화면의 캡처가 없습니다.'))+'</div>';
}
render=function(){
  BASE_RENDER();
  document.querySelectorAll('[data-display-mode]').forEach(button=>{const active=button.dataset.displayMode===GROUP_UI.mode;button.classList.toggle('active',active);button.setAttribute('aria-pressed',String(active));});
  document.getElementById('search').disabled=GROUP_UI.mode==='native';
  document.getElementById('export-data').disabled=GROUP_UI.mode==='native';
  if(GROUP_UI.mode==='native')ROOT.innerHTML=nativeView();
  document.getElementById('footer-status').textContent=GROUP_UI.mode==='native'?'1.4.22 실제 확장 캡처 · 합성 검증 데이터':'1.4.22 그룹형 표시 · 1.4.21 은행 저장 결과 · 실습용 $';
};
ROOT.addEventListener('click',event=>{
  const group=event.target.closest('[data-result-group]'),leaf=event.target.closest('[data-result-leaf]');
  if(group||leaf){
    event.preventDefault();const button=group||leaf,key=button.dataset.resultOwner,context=groupContext(key);
    if(group?.dataset.resultGroup==='all')delete GROUP_UI.filters[context];
    else GROUP_UI.filters[context]={group:group?group.dataset.resultGroup:leaf.dataset.resultParent,leaf:leaf?leaf.dataset.resultLeaf:''};
    UI.sel[key]=0;render();return;
  }
  if(event.target.closest('[data-native-zoom]')){GROUP_UI.zoom=!GROUP_UI.zoom;render();}
});
ROOT.addEventListener('toggle',event=>{
  if(event.target.dataset.resultFolder)GROUP_UI.expanded[event.target.dataset.resultFolder]=event.target.open;
  if(event.target.dataset.resultOriginalContext)GROUP_UI.originals[event.target.dataset.resultOriginalContext]=event.target.open;
},true);
document.querySelectorAll('[data-display-mode]').forEach(button=>button.addEventListener('click',()=>{GROUP_UI.mode=button.dataset.displayMode;render();}));
UI.page='dashboard';
window.WorkbenchGroupedResults={presentation:PRESENTATION,state:GROUP_UI,routeLabel,rowPresentation};
