"""Package actual final Swing captures alongside the approved design reference.

Run only after the root agent declares tmp/approved-final-ui final and passed.
No production Simple inputs are written.
"""
from pathlib import Path
import hashlib,html,io,json,shutil,zipfile
import fitz
from fontTools.ttLib import TTFont
from fontTools import subset
from PIL import Image

R=Path('/workspace/burp-workbench-kr')
D=R/'docs/reports/2026-10-31-approved-workflow'
SOURCE=R/'tmp/approved-final-ui'
REFERENCE=R/'docs/reports/2026-10-31-workflow-design'
ORDER=['traffic','mapper','js','environment','exposure.search','exposure.cve','auth','response.recon','payload','data','activity','bounty','dashboard','payload.results','bounty.replay','auth.accounts']
SOURCES={'traffic':'02-request.png','mapper':'08-mapper.png','js':'10-js.png','environment':'09-environment.png','exposure.search':'12-exposure-search.png','exposure.cve':'13-exposure-cve.png','auth':'04-auth-results.png','response.recon':'03-analysis.png','payload':'07-payload.png','data':'14-data.png','activity':'15-progress.png','bounty':'05-evidence.png','dashboard':'16-dashboard.png','payload.results':'07-payload-results.png','bounty.replay':'11-replay.png','auth.accounts':'04-auth-accounts.png'}
NOTES={
 'traffic':[
  '선택 요청의 문맥과 Repeater 준비를 묶고, 비교·입력값 검사·증거 연결은 요청 활용 메뉴로 정리했습니다.',
  '대상·계정·수집 상태와 검색·필터를 구분하고, 패킷의 보조 도구는 필요할 때 열도록 구성했습니다.',
  '기존 원본 ID와 준비 동작을 사용하며 화면 이동·선택만으로 대상 요청을 전송하지 않습니다.'],
 'mapper':[
  '기능 트리와 요청 목록을 중심에 두고, 선택 요청의 분류 수정을 주 동작으로 구분했습니다.',
  '프로젝트 관리·기능 편집·선택 요청 작업을 각 문맥의 메뉴로 묶었습니다.',
  '기존 매핑·세션·원문 모델과 수집 경로를 유지하면서 요청·응답과 분류 근거를 확인합니다.'],
 'js':[
  '발견 경로 목록을 왼쪽, 선택 소스를 오른쪽에 배치해 목록과 호출 코드를 함께 확인합니다.',
  '반복 사실표·원문 탐색은 접힘 영역으로 옮기고, 주요 세 유형 외 결과는 분석 유형 메뉴로 묶었습니다.',
  'Repeater 준비를 강조하며 원문 위치·관찰 패킷·분석 관리는 관련 메뉴에 유지했습니다.'],
 'environment':[
  '후보 목록은 왼쪽, 선택 경로의 응답·요청·판정 근거는 오른쪽 탭으로 구성했습니다.',
  '선택 GET 확인과 일시정지·중지는 가까이 두고, 일괄 실행·재개·저장 관리는 메뉴로 묶었습니다.',
  '환경 단서는 접어서 확인하며 대상·계정·실제 GET 전송 조건과 기존 검증 로직을 유지합니다.'],
 'exposure.search':[
  '검색식 작성에서 가져온 URL 목록, 선택 URL의 발견 근거로 이어지는 두 영역을 구성했습니다.',
  'Google 열기를 주 동작으로 두고 가져오기·목록 관리·선택 근거 활용을 분리했습니다.',
  'URL 가져오기는 노출 확인 완료를 뜻하지 않으며 Repeater 준비도 기존 SCOPE 검사를 거칩니다.'],
 'exposure.cve':[
  '관측 제품을 한 곳에서 선택하고 수동 수정·세부 필터는 필요할 때 펼치게 했습니다.',
  '결과 목록과 선택 후보를 나란히 두고, 다섯 개 동등한 박스를 적용 조건·관측 근거 탭으로 통합했습니다.',
  '영향 범위·수정 버전 확인·원본 ID·제공자 최신성 및 미확인 상태를 기존 데이터로 표시합니다.'],
 'auth':[
  '역할·이름·연결 상태를 작은 클릭 가능한 요약으로 정리해 응답을 읽을 공간을 확보했습니다.',
  '비교 준비를 기록 문맥에 두고 계정·정책·보완 작업은 관련 메뉴로 묶었습니다.',
  '연결된 후보와 실제 검증된 권한을 구분하며 기존 계정·원문·판정 및 명시적 실행 조건을 유지합니다.'],
 'response.recon':[
  '관찰 목록과 선택 항목의 원문 검사 영역을 나란히 배치했습니다.',
  '반복되는 요약을 압축하고 근거·관련 요청·영향 URL을 같은 영역에서 확인합니다.',
  '증거 정리를 주 동작으로 두며 관찰 후보를 실제 취약점 확인 완료로 표현하지 않습니다.'],
 'payload':[
  '요청 편집과 검사 조건을 함께 볼 수 있도록 준비 흐름을 구성했습니다.',
  '후보·고급 설정은 접어서 열고 대상·인증·검사 위치·요청 한도 같은 필수 조건은 확인할 수 있습니다.',
  '검사 시작·중지를 함께 유지하며 표시·정렬·보기 전환으로 작성한 요청 바이트를 변경하지 않습니다.'],
 'data':[
  '변환 방식과 실행을 공통 작업줄로 묶고 입력과 출력을 나란히 대조합니다.',
  '복사는 출력 문맥에 두고 출력 재사용은 출력 작업 메뉴로 구분했습니다.',
  'JWT와 응답 비교 등 기존 모드를 유지하며 변환은 기존 로컬 엔진을 사용합니다.'],
 'activity':[
  'URL 목록과 선택 항목의 입력·근거를 연결해 남은 항목을 검토합니다.',
  '완료 표시는 현재 선택 대상과 함께 배치하고 기록 관리는 이름 있는 메뉴로 정리했습니다.',
  'URL 완료·개별 입력 완료를 구분하며 기존 상태와 원본 연결을 유지합니다.'],
 'bounty':[
  '증거 목록·보고서 작성·원문 대조를 구분해 영향과 재현 절차를 읽으며 작성합니다.',
  '항목별 이동·삭제·메모는 항목 작업으로, 가림 미리보기·ZIP 저장은 문서 작업으로 묶었습니다.',
  '실행 취소·초안과 선택 원본 ID를 유지하며 저장은 기존 가림·증거 패키지 경로를 사용합니다.'],
 'dashboard':[
  '분류 요약과 결과 목록을 중심에 두고 중복 분포 그래프는 접히는 상세로 옮겼습니다.',
  '선택 근거의 원본 보기를 강조하고 Repeater·증거 연결은 다음 작업 메뉴로 묶었습니다.',
  '현재 실행의 관측 결과와 전송·예산 정보를 구분하며 대시보드 표시를 위해 새 검사를 시작하지 않습니다.'],
 'payload.results':[
  '결과 표에서 선택한 항목의 판정 요약과 실제 요청·응답으로 이어지게 구성했습니다.',
  '결과 증거 연결을 강조하고 Repeater와 보조 관리는 관련 메뉴에 유지했습니다.',
  '반사 관찰과 브라우저 실행 확인을 구분하며 실제 전송·중지·원본 연결 계약을 유지합니다.'],
 'bounty.replay':[
  '준비 요청·실행 결과·선택 단계 원문을 서로 다른 작업 영역으로 구분했습니다.',
  '대상·쓰기 허용·요청 한도를 요약하고 변수 추출은 필요한 경우 펼칩니다.',
  '순차 실행과 중지를 함께 두며 결과의 증거 연결은 실행 결과 문맥에 유지합니다.'],
 'auth.accounts':[
  '역할과 인증 후보를 같은 행에서 확인하고 미사용 추가 계정은 접어 정리했습니다.',
  '긴 신원 정보는 확인 가능한 설명으로 유지하며 첫 연결을 검증된 기준 계정으로 확정하지 않습니다.',
  '기존 선택 즉시 반영 방식을 유지하고 별도의 새 적용 단계나 자동 전송을 추가하지 않았습니다.']}

def digest(path):
 with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def write_json(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def fitted_rect(x,y,width,height,image_path):
 with Image.open(image_path) as im:iw,ih=im.size
 scale=min(width/iw,height/ih)
 return fitz.Rect(x,y,x+iw*scale,y+ih*scale)
def wrap_text(page,rect,text,size,color):
 remaining=page.insert_textbox(rect,text,fontname='KR',fontsize=size,color=color,lineheight=1.38)
 assert remaining>=0,('PDF text overflow',text,remaining)

def main():
 layout=json.loads((SOURCE/'layout-report.json').read_text())
 assert layout['version']=='1.4.16' and layout['result']=='passed','Final approved layout is not ready/passed'
 assert not layout.get('missingCaptures') and not layout.get('failedScenarios')
 artifact=R/'simple/burp-workbench/dist/burp-workbench-simple-1.4.16.jar'
 assert digest(artifact)==layout['jarSha256'],'Capture report and current JAR differ'
 approved=json.loads((REFERENCE/'catalog.json').read_text())
 guides=json.loads((R/'simple/burp-workbench/src/main/resources/guides/catalog.json').read_text())['guides']
 guide_map={v['id']:v for v in guides};assert set(ORDER)==set(approved)==set(guide_map)
 D.mkdir(parents=True,exist_ok=True);manifest=[]
 for theme in ['light','dark']:
  folder=D/'images'/theme;folder.mkdir(parents=True,exist_ok=True)
  for key in ORDER:
   src=SOURCE/theme/'1600x1000'/SOURCES[key];dst=folder/guide_map[key]['image'];assert src.is_file(),src
   shutil.copyfile(src,dst)
   with Image.open(src) as im:width,height=im.size
   assert (width,height)==((1000,300) if key=='auth.accounts' else (1600,1000)),(key,theme,width,height)
   manifest.append({'screen':key,'theme':theme,'source':str(src.relative_to(R)),'file':str(dst.relative_to(D)),'width':width,'height':height,'sha256':digest(dst)})
 write_json(D/'image-manifest.json',{'version':'1.4.16','jarSha256':layout['jarSha256'],'actualScreens':32,'files':manifest})
 mapping={k:{'title':guide_map[k]['title'],'goal':approved[k]['goal'],'approvedPrimary':approved[k]['primary'],'implementationNotes':NOTES[k],'scope':'Workflow/layout mapping, not pixel equality.'} for k in ORDER}
 write_json(D/'implementation-map.json',mapping)
 labels=['Workbench Simple 1.4.16','승인한 작업 흐름 적용 화면','실제 구현','승인 시안','설계의 핵심','반영한 작업 흐름','주 동작','라이트','다크','실제 운영 Swing 컴포넌트에 합성 데이터를 넣은 캡처입니다. Windows Burp 본체·DPI/IME 검증은 포함하지 않습니다.','구성·기능 묶음·읽기 흐름을 비교하세요. 시안과 픽셀 단위 동일성을 의미하지 않습니다.']
 chars=''.join(chr(n) for n in range(32,127))+''.join(labels)+''.join(v['title']+v['goal']+v['primary']+''.join(v['rationale']) for v in approved.values())+''.join(v['title'] for v in guides)+''.join(''.join(v) for v in NOTES.values())
 font=Path('/tmp/approved-report-font.ttf');f=TTFont(R/'simple/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf');sub=subset.Subsetter();sub.populate(text=chars);sub.subset(f);f.save(font)
 pdf=fitz.open();toc=[];expected=[]
 for n,key in enumerate(ORDER):
  title=guide_map[key]['title'];obj=approved[key]
  for theme in ['light','dark']:
   page=pdf.new_page(width=2000,height=1200);page.insert_font(fontname='KR',fontfile=str(font));page.draw_rect(page.rect,color=None,fill=(.965,.972,.983))
   page.insert_text((32,34),'Workbench Simple 1.4.16 · 승인한 작업 흐름 적용 화면',fontname='KR',fontsize=14,color=(0,.4,.8))
   page.insert_text((32,79),title+' · '+('라이트' if theme=='light' else '다크'),fontname='KR',fontsize=29,color=(.10,.13,.18))
   page.insert_text((32,122),'실제 구현',fontname='KR',fontsize=17,color=(0,.4,.8));page.insert_text((1600,122),'승인 시안',fontname='KR',fontsize=17,color=(.38,.43,.50))
   actual=D/'images'/theme/guide_map[key]['image'];reference=REFERENCE/'images'/theme/(key+'.png');assert reference.is_file(),reference
   page.insert_image(fitted_rect(32,143,1530,956.25,actual),filename=str(actual),keep_proportion=True)
   page.insert_image(fitted_rect(1600,143,368,230,reference),filename=str(reference),keep_proportion=True)
   expected.append([actual,reference])
   page.insert_text((1600,412),'반영한 작업 흐름',fontname='KR',fontsize=18,color=(.10,.13,.18))
   wrap_text(page,fitz.Rect(1600,436,1968,494),'주 동작 · '+obj['primary'],15,(0,.35,.73))
   for i,note in enumerate(NOTES[key]):wrap_text(page,fitz.Rect(1600,507+i*145,1968,641+i*145),str(i+1)+'. '+note,14,(.25,.30,.38))
   wrap_text(page,fitz.Rect(1600,960,1968,1099),labels[-1],12,(.43,.47,.54))
   page.insert_text((32,1165),labels[-2],fontname='KR',fontsize=12,color=(.43,.47,.54))
   page.insert_text((1878,1165),str(len(pdf))+' / 32',fontname='KR',fontsize=12,color=(.43,.47,.54));toc.append([1,title+' · '+('라이트' if theme=='light' else '다크'),len(pdf)])
 pdf.set_toc(toc);pdf.set_metadata({'title':'Workbench Simple1.4.16 · 승인한 작업 흐름 적용 화면','author':'Workbench KR','subject':'Actual synthetic Swing captures beside the approved design. Not Windows Burp GUI or pixel equality.'})
 output=D/'workbench-simple-1.4.16-workflow.pdf';pdf.save(output,garbage=4,deflate=True);pdf.close();font.unlink()
 doc=fitz.open(output);pixel_checks=[]
 assert len(doc)==32
 for index,page in enumerate(doc):
  images=page.get_images();assert len(images)==2,(index,len(images))
  for entry,original in zip(images,expected[index]):
   embedded=Image.open(io.BytesIO(doc.extract_image(entry[0])['image'])).convert('RGB');source=Image.open(original).convert('RGB')
   assert embedded.size==source.size and embedded.tobytes()==source.tobytes(),(index,original)
   for placed in page.get_image_rects(entry[0]):assert abs(placed.width/placed.height-source.width/source.height)<.0001,(index,original,'aspect ratio')
   pixel_checks.append({'page':index+1,'source':str(original.relative_to(R)),'sourcePixelMatch':True})
  for block in page.get_text('dict')['blocks']:
   for line in block.get('lines',[]):
    for span in line['spans']:assert page.rect.contains(fitz.Rect(span['bbox'])),(index,span['text'])
 write_json(D/'pdf-verification.json',{'jarSha256':layout['jarSha256'],'pages':32,'bookmarks':len(doc.get_toc()),'embeddedImages':64,'actualImages':32,'approvedReferenceImages':32,'sourcePixelsMatch':True,'textBoundsPassed':True,'bytes':output.stat().st_size,'sha256':digest(output),'checks':pixel_checks})
 # Separate overview sheets show all actual screens; source PNGs remain unchanged.
 for theme in ['light','dark']:
  contact=fitz.open();page=contact.new_page(width=2400,height=1740);page.insert_font(fontname='KR',fontfile=str(R/'simple/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf'))
  page.draw_rect(page.rect,color=None,fill=(.965,.972,.983));page.insert_text((22,34),'Workbench Simple1.4.16 · '+('라이트' if theme=='light' else '다크')+' · 실제 적용 16화면',fontname='KR',fontsize=23,color=(.1,.13,.18))
  for index,key in enumerate(ORDER):
   x=(index%4)*600;y=54+(index//4)*416
   page.insert_text((x+10,y+23),str(index+1)+'. '+guide_map[key]['title'],fontname='KR',fontsize=14,color=(.2,.25,.33))
   thumb=D/'images'/theme/guide_map[key]['image']
   page.insert_image(fitted_rect(x+7,y+31,586,366.25,thumb),filename=str(thumb),keep_proportion=True)
  page.get_pixmap().save(D/('contact-'+theme+'.png'));contact.close()
 # Representative review previews; these are PDF rasterizations, not altered UI data.
 for index,name in [(0,'traffic-comparison.png'),(12,'auth-comparison.png'),(22,'evidence-comparison.png'),(16,'payload-comparison.png'),(30,'accounts-comparison.png')]:doc[index].get_pixmap(matrix=fitz.Matrix(.75,.75)).save(D/name)
 doc.close()
 web='''<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Simple1.4.16 실제 적용 화면</title><style>body{margin:28px;font:16px system-ui;background:#f5f6f8;color:#202630}h1{font-size:26px}p{line-height:1.8;color:#657286}section{margin:36px 0}article{display:grid;grid-template-columns:1fr 1fr;gap:16px}img{width:100%;border:1px solid #ddd;border-radius:10px}figure{margin:0}figcaption{margin:8px 0}a{color:#0067d6}li{margin:8px 0;line-height:1.7}@media(max-width:850px){article{grid-template-columns:1fr}}</style><h1>Simple1.4.16 · 승인한 작업 흐름 적용 화면</h1><p>실제 Swing 컴포넌트와 합성 데이터를 사용한 캡처입니다. Windows Burp 본체 캡처와 시안의 픽셀 동일성을 의미하지 않습니다.<br><a href="workbench-simple-1.4.16-workflow.pdf">승인 시안과 실제 화면 PDF</a> · 이미지를 누르면 원본 크기로 열립니다.</p>'''
 for n,key in enumerate(ORDER):
  obj=mapping[key];web+='<section><h2>'+str(n+1)+'. '+html.escape(obj['title'])+'</h2><p>'+html.escape(obj['goal'])+'</p><article>'
  for theme,label in [('light','라이트'),('dark','다크')]:
   path='images/'+theme+'/'+guide_map[key]['image'];web+='<figure><figcaption>'+label+'</figcaption><a href="'+path+'"><img src="'+path+'" loading="lazy" alt="'+html.escape(obj['title'])+' '+label+' 실제 적용 화면"></a></figure>'
  web+='</article><ul>'+''.join('<li>'+html.escape(note)+'</li>' for note in NOTES[key])+'</ul></section>'
 (D/'gallery.html').write_text(web+'</html>')
 readme='''# Simple1.4.16 · 승인한 작업 흐름 적용 화면

**[실제 화면 + 승인 시안 PDF](workbench-simple-1.4.16-workflow.pdf)** · **[32장 이미지·갤러리 ZIP](workbench-simple-1.4.16-images.zip)** · [한곳에서 보기](gallery.html)

버튼 위치만 옮기던 이전 변경에서 벗어나 사용자가 승인한16개 설계안의 작업 흐름·우선순위·메뉴 통합·접히는 설정·읽기 영역을 실제 Swing 화면에 적용했습니다. 기능 모델·원본 ID·작성 데이터·명시적 전송 조건을 사용하는 기존 엔진을 유지합니다.

PDF는16화면×라이트·다크의32페이지입니다. 실제 구현을 크게, 승인한 시안을 작은 참고 화면으로 배치하고 반영한 작업 흐름을 설명합니다. 계정 연결 화면의1000×300패널과 나머지1600×1000화면은 원본 비율과 픽셀을 유지합니다. 두 화면의 합성 예시 데이터는 다르며 픽셀 단위 동일성을 주장하지 않습니다.

## 화면별 반영

| 화면 | 주 작업 | 라이트 | 다크 |
|---|---|---|---|
'''
 for key in ORDER:
  obj=mapping[key];name=guide_map[key]['image'];readme+='| '+obj['title']+' | '+obj['approvedPrimary']+' | [보기](images/light/'+name+') | [보기](images/dark/'+name+') |\n'
 readme+='''
## 구현 의미

'''
 for key in ORDER:readme+='### '+mapping[key]['title']+'\n\n'+''.join('- '+note+'\n' for note in NOTES[key])+'\n'
 readme+='''## 확인 범위

이미지는 합성 데이터를 넣은 실제 운영 Swing 컴포넌트 캡처입니다. 실제 Windows Burp 본체·DPI/IME 검증은 포함하지 않습니다. [캡처 목록](image-manifest.json) · [시안→구현 매핑](implementation-map.json) · [PDF 검사](pdf-verification.json).

PDF 삽입64장(실제32/시안32)은 각각 원본 픽셀과 일치하며 글자·이미지 경계를 확인했습니다. [라이트 전체](contact-light.png) · [다크 전체](contact-dark.png).

전체 기능·배치·실제 별도 창·Katana·Guide·패키지 검증의 최종 결과는 이 폴더의VERIFICATION.json과 배포 기록에 별도로 기록합니다. 캡처 생성만으로 실제Burp/외부제공자 검증을 수행했다고 표현하지 않습니다.
'''
 (D/'README.md').write_text(readme)
 (D/'README_KR.txt').write_text('승인한 작업 흐름을 반영한 Simple1.4.16 실제 화면 모음입니다.\n\ngallery.html 또는 workbench-simple-1.4.16-workflow.pdf를 열어32개 실제 화면을 한곳에서 검토하세요.\nPNG는 images/light와images/dark에 있습니다. contact-light.png/contact-dark.png는16화면 미리보기입니다.\n\n합성 데이터를 넣은 실제 Swing 캡처이며 Windows Burp 본체·DPI/IME 검증과 시안의 픽셀 동일성을 의미하지 않습니다.\n')
 bundle=D/'workbench-simple-1.4.16-images.zip'
 with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as z:
  for path in [D/'gallery.html',D/'README_KR.txt',output,D/'contact-light.png',D/'contact-dark.png',*sorted((D/'images').rglob('*.png'))]:z.write(path,path.relative_to(D))
 with zipfile.ZipFile(bundle) as z:assert len(z.namelist())==37 and z.testzip() is None
 write_json(D/'artifact-verification.json',{'version':'1.4.16','jarSha256':layout['jarSha256'],'actualScreens':32,'themes':2,'pdfPages':32,'pdfSourcePixelMatches':64,'contactSheets':2,'zipFiles':37,'zipCrcPassed':True,'pdfSha256':digest(output),'imageZipSha256':digest(bundle),'scope':'Actual synthetic Swing captures and approved reference, not native Windows Burp.'})
 print('Actual32PNG / reference32 /32pagePDF /2contact sheets /37fileZIP generated;64 source-pixel matches.')

if __name__=='__main__':main()
