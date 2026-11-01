#!/usr/bin/env python3
"""Embed the verified bank report, original screenshots and evidence into one offline HTML."""
import argparse,base64,hashlib,html,json,mimetypes,re
from pathlib import Path

BOOTSTRAP=r'''
<script id="embedded-assets" type="application/json">__ASSETS__</script>
<dialog id="attachment-viewer"><div class="attachment-bar"><strong id="attachment-title"></strong><a id="attachment-download">다운로드</a><button id="attachment-close" type="button">닫기</button></div><pre id="attachment-text"></pre></dialog>
<script>
(()=>{
  const assets=JSON.parse(document.getElementById('embedded-assets').textContent), urls=new Map();
  function bytes(name){return Uint8Array.from(atob(assets[name].base64),c=>c.charCodeAt(0));}
  function url(name){if(!urls.has(name))urls.set(name,URL.createObjectURL(new Blob([bytes(name)],{type:assets[name].mime})));return urls.get(name);}
  const dialog=document.getElementById('attachment-viewer');
  document.getElementById('attachment-close').onclick=()=>dialog.close();
  document.querySelectorAll('a[data-embedded-href]').forEach(a=>{
    const name=a.dataset.embeddedHref;a.href=url(name);
    if(a.classList.contains('shotlink')||name.endsWith('.png')){a.target='_blank';a.rel='noopener';return;}
    a.download=assets[name].filename;
    if(/\.(json|md)$/.test(name))a.addEventListener('click',event=>{
      event.preventDefault();let text=new TextDecoder().decode(bytes(name));
      if(name.endsWith('.json'))text=JSON.stringify(JSON.parse(text),null,2);
      document.getElementById('attachment-title').textContent=assets[name].filename;
      document.getElementById('attachment-text').textContent=text;
      const download=document.getElementById('attachment-download');download.href=url(name);download.download=assets[name].filename;
      dialog.showModal();
    });
  });
  document.querySelectorAll('img[data-embedded-src]').forEach(img=>{
    img.src=url(img.dataset.embeddedSrc);
    if(img.dataset.embeddedLight)img.dataset.light=url(img.dataset.embeddedLight);
    if(img.dataset.embeddedDark)img.dataset.dark=url(img.dataset.embeddedDark);
  });
  window.__embeddedReport={assetCount:Object.keys(assets).length,singleFile:true};
})();
</script>
'''

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--report',type=Path,required=True)
    a=p.parse_args();root=a.report.resolve();output=root/'standalone/Workbench_Simple_1.4.21_Bank_Results_KR.html'
    if output.exists():raise RuntimeError('Preserve an existing standalone report; choose a new version before replacing it.')
    verified=json.loads((root/'verification.json').read_text());assert verified['result']=='passed'
    frozen=json.loads((root/'package-verification.json').read_text())['sources']
    text=(root/'RESULTS.html').read_text();assets={}
    extra=[]
    for label,name in [
        ('은행 로그인','images/bank/01-login.png'),('은행 초기 계좌·잔액','images/bank/02-dashboard-light.png'),
        ('은행 이체 양식 · 제출 전','images/bank/03-transfer-form.png'),('은행 초기 거래 내역','images/bank/04-transactions.png'),
        ('은행 다크 화면','images/bank/05-dashboard-dark.png'),
        ('음수 이체 후 Alice1005','images/business/alice-after.png'),('음수 이체 후 Bob995','images/business/bob-after.png'),
        ('계정별 판정 근거 · 라이트','images/workbench/light/auth-judgement.png'),('계정별 판정 근거 · 다크','images/workbench/dark/auth-judgement.png')]:
        extra.append(f'<figure><figcaption>{html.escape(label)}</figcaption><a href="{name}"><img loading="lazy" src="{name}" alt="{html.escape(label)}"></a></figure>')
    text=text.replace('<footer>','<details class="tool"><summary>추가 실제 화면 · 은행 / 음수 이체 / 판정 근거</summary>'+''.join(extra)+'</details><footer>',1)
    # Additional galleries do not participate in the original13-tool filter.
    text=text.replace('<details class="tool"><summary>추가 실제 화면','<details class="extra-gallery"><summary>추가 실제 화면')
    text=text.replace('<title>은행 실습 · Workbench Simple 1.4.21 결과</title>','<title>확장 도구별 결과 · Workbench Simple 1.4.21 · 단일 HTML</title>')
    text=text.replace('<h1>도구별로 실제 얻은 결과</h1>','<h1>확장 도구별로 실제 얻은 결과</h1><p>이미지·근거를 포함한 단일 HTML입니다. 이 파일만 저장해 브라우저에서 열 수 있습니다. 통화 표시는 실습용 달러($)입니다.</p>')
    styles='''<style>#attachment-viewer{width:min(1100px,94vw);max-height:90vh;border:1px solid #b9c9d8;border-radius:12px;padding:20px}#attachment-viewer::backdrop{background:#0b1d35aa}.attachment-bar{display:flex;align-items:center;gap:18px;position:sticky;top:0;background:white;padding:8px 0}.attachment-bar strong{flex:1;overflow-wrap:anywhere}#attachment-text{white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.6 ui-monospace,monospace}.extra-gallery{padding:25px;border:1px solid #dfe6ee;border-radius:13px;background:white;margin:18px 0}.extra-gallery figure{margin:20px 0}</style>'''
    policy='''<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'">'''
    text=text.replace('<main>',styles+'<main>',1).replace('<meta charset="utf-8">','<meta charset="utf-8">'+policy,1)
    def embed(match):
        attr,name=match.groups()
        if name.startswith(('#','data:','https:','http:')):raise RuntimeError('Unexpected external or unresolved report link: '+name)
        source=(root/name).resolve();assert source.is_relative_to(root) and source.is_file(),name
        data=source.read_bytes();digest=hashlib.sha256(data).hexdigest()
        assert frozen.get(name)==digest,'Input differs from the frozen verified report: '+name
        if name not in assets:
            mime=mimetypes.guess_type(name)[0] or 'application/octet-stream'
            assets[name]={'mime':mime,'filename':source.name,'sha256':digest,'bytes':len(data),'base64':base64.b64encode(data).decode()}
        key={'href':'href','src':'src','data-light':'light','data-dark':'dark'}[attr]
        return ('href="#" ' if attr=='href' else '')+f'data-embedded-{key}="{html.escape(name,quote=True)}"'
    text=re.sub(r'\b(href|src|data-light|data-dark)="([^"]+)"',embed,text)
    marker='<script>let dark=false;';assert marker in text
    payload=json.dumps(assets,ensure_ascii=True,separators=(',',':')).replace('<','\\u003c')
    text=text.replace(marker,BOOTSTRAP.replace('__ASSETS__',payload)+marker,1)
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(text)
    manifest={'format':'standalone-bank-results-html','version':'1.4.21','sourceReport':'../RESULTS.html',
              'sourceCoreJarSha256':verified['jarSha256'],'output':output.name,'outputBytes':output.stat().st_size,
              'outputSha256':hashlib.sha256(output.read_bytes()).hexdigest(),'newBankRequests':0,
              'toolRows':13,'embeddedOriginalPngs':sum(n.endswith('.png') for n in assets),'assetCount':len(assets),
              'assets':{k:{a:b for a,b in v.items() if a!='base64'} for k,v in assets.items()},
              'inputsMatchPreviouslyVerifiedFrozenPublicReport':True,'relativeRuntimeAssetsRequired':False}
    (output.parent/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in manifest.items() if k!='assets'},ensure_ascii=False))
if __name__=='__main__':main()
