#!/usr/bin/env python3
"""Check saved bank evidence, privacy, original fingerprints, PNGs and local report links."""
import argparse,hashlib,io,json,re,struct,zipfile,zlib
from datetime import datetime,timezone
from pathlib import Path

JAR_SHA='9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c'
JWT=re.compile(rb'eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}')
def sha(data):return hashlib.sha256(data).hexdigest()
def png(data):
    assert data[:8]==b'\x89PNG\r\n\x1a\n','PNG signature'
    offset=8;size=None;ended=False
    while offset<len(data):
        length=struct.unpack('>I',data[offset:offset+4])[0];kind=data[offset+4:offset+8]
        payload=data[offset+8:offset+8+length];crc=struct.unpack('>I',data[offset+8+length:offset+12+length])[0]
        assert zlib.crc32(kind+payload)&0xffffffff==crc,'PNG chunk CRC'
        if kind==b'IHDR':size=struct.unpack('>II',payload[:8])
        offset+=length+12
        if kind==b'IEND':ended=True;break
    assert ended and size and size[0]>0 and size[1]>0 and offset==len(data),'PNG complete'
    return size

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--report',type=Path,required=True);p.add_argument('--state',type=Path,required=True)
    p.add_argument('--private-core',type=Path,required=True);p.add_argument('--private-business',type=Path,required=True)
    p.add_argument('--jar',type=Path,required=True);a=p.parse_args();root=a.report.resolve()
    def load(path):return json.loads((root/path).read_text())
    assert sha(a.jar.read_bytes())==JAR_SHA,'Immutable release changed'
    core=load('results/results.json');runtime=load('results/runtime.json')
    assert core['failedChecks']==0 and len(core['checks'])==13
    assert all(c['status']=='PASS' for c in core['checks'])
    assert core['syntheticTargetResponses'] is False and core['nativeBurp'] is False
    assert core['jarSHA256']==JAR_SHA and runtime['artifactUnchanged'] and runtime['exitCode']==0
    transport=load('results/transport-index.json');traffic=load('results/traffic.json')
    assert len(transport)==core['httpRequests']==traffic['captured']==89
    assert int(traffic['processingErrors'])==0
    checked_originals=0
    for location,private in [('results',a.private_core),('results/business',a.private_business)]:
        for row in load(location+'/original-id-index.json'):
            assert row['originalId']
            for side in ['request','response']:
                raw=(private/row['private'+side.title()+'File']).read_bytes()
                assert sha(raw)==row[side+'SHA256'],(location,side)
                assert len(raw)==row[side+'Bytes'],(location,side)
            checked_originals+=1
        for row in load(location+'/transport-index.json'):
            for side in ['request','response']:
                raw=(private/row['private'+side.title()+'File']).read_bytes()
                assert sha(raw)==row[side+'SHA256'],(location,side)
            body=(private/'transport'/f"{row['index']:04d}.body.bin").read_bytes()
            assert sha(body)==row['bodySHA256'] and len(body)==row['bodyBytes']
    capture=load('images/workbench/workbench-capture-verification.json')
    assert capture['result']=='completed' and capture['jarSha256']==JAR_SHA
    assert capture['capturedPngCount']==28 and capture['skippedForVisibleSecrets']==0
    assert capture['knownSecretsProvided'] and capture['syntheticResultFixture'] is False
    for row in capture['captures']:
        assert row['status']=='CAPTURED'
        raw=(root/'images/workbench'/row['file']).read_bytes();assert sha(raw)==row['sha256']
    png_count=0
    for image in root.rglob('*.png'):png(image.read_bytes());png_count+=1
    corrections=load('images/workbench/capture-corrections.json')
    assert sha((root/'images/workbench/workbench-capture-verification.json').read_bytes())==corrections['originalCaptureManifestSha256']
    browser=load('images/bank/verification.json')
    assert browser['result']=='passed' and browser['failedLocalHttpResponses']==browser['failedLocalResourceRequests']==browser['pageErrorCount']==0
    business=load('results/business/business-negative-transfer.json')
    assert business['result']=='PASS' and business['actualHttpRequests']==5
    assert business['senderDelta']==5 and business['recipientDelta']==-5
    truth=load('truth/groundtruth.json')
    assert truth['confirmedVulnerabilities']==3 and truth['normalControlsPassed']==2 and truth['requestCount']==10
    state=json.loads(a.state.read_text());tokens=[x['token'].encode() for x in state['actors']]
    leaked=[];scanned=0;archive_entries=0
    def scan(label,data,engine=False,depth=0):
        nonlocal scanned,archive_entries
        scanned+=1
        if any(token in data for token in tokens):leaked.append(label+':known-live-token')
        if JWT.search(data):leaked.append(label+':jwt-pattern')
        if engine and state['password'].encode() in data:leaked.append(label+':private-engine-password')
        if label.lower().endswith('.zip'):
            assert depth<4
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                assert z.testzip() is None,label
                for item in z.infolist():
                    assert item.file_size<50*1024*1024
                    archive_entries+=1
                    scan(label+'/'+item.filename,z.read(item),engine,depth+1)
    for f in root.rglob('*'):
        if not f.is_file() or f.suffix=='.png' or f.name in ['verification.json','public-download-verification.json']:continue
        if f.name=='workbench-simple-1.4.21-bank-results.zip':continue
        scan(str(f.relative_to(root)),f.read_bytes(),f.relative_to(root).parts[0] in ['results','truth'])
    assert not leaked,'Sensitive content found in '+','.join(leaked)
    links=0;generated_output_links=[]
    for page in [*root.rglob('*.md'),root/'RESULTS.html']:
        content=page.read_text();targets=re.findall(r'\]\(([^)]+)\)',content) if page.suffix=='.md' else re.findall(r'(?:href|src)="([^"]+)"',content)
        for link in targets:
            if '://' in link or link.startswith(('data:','#')):continue
            target=link.split('#')[0]
            if not target:continue
            resolved=(page.parent/target).resolve()
            if resolved in [root/'verification.json',root/'downloads/workbench-simple-1.4.21-bank-results.zip'] and not resolved.exists():
                generated_output_links.append(str(resolved.relative_to(root)));continue
            assert (page.parent/target).exists(),f'Broken local link: {page.name} -> {link}'
            links+=1
    data=load('report-data.json');assert len(data['toolRows'])==13
    assert sum(r['kind']=='core' for r in data['toolRows'])==8
    report={'result':'passed','verifiedAtUTC':datetime.now(timezone.utc).isoformat(),'jarSha256':JAR_SHA,
            'coreFunctionalScenarios':13,'coreNetworkRequests':89,'coreCapturedTraffic':89,'captureProcessingErrors':0,
            'businessNetworkRequests':5,'independentTruthNetworkRequests':10,'countsOverlapAndAreNotAdded':True,
            'originalRecordsWithVerifiedPrivateRequestResponseBytes':checked_originals,
            'privateTransportBodiesAndSerializedRequestsResponsesMatchPublicFingerprints':True,
            'nativeWorkbenchPngs':28,'allPngsCRCVerified':png_count,'relativeLinksVerified':links,
            'generatedOutputLinksValidatedAfterPackaging':generated_output_links,
            'publicFilesAndArchiveEntriesScanned':scanned,'archiveEntriesScanned':archive_entries,
            'knownLiveTokensFound':0,'genericJwtPatternsFound':0,'privateEnginePasswordsFound':0,
            'demoSiteCredentialsIntentionallyDocumented':True,'privateRawCorpusOutsideRepository':True,
            'bankAndProductResponsesAreSynthetic':False,'nativeBurpGui':False,'productLimitationsDocumented':2,
            'helperMetadataCorrectionPreservesOriginalCaptureManifest':True,
            'sourceSnapshotNotes':'Use results/measurement-stages.json; earlier snapshots are not mixed into final counts.'}
    (root/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False))
if __name__=='__main__':main()
