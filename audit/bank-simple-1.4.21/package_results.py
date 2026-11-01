#!/usr/bin/env python3
"""Package only the privacy-checked public bank report and verify every archived file."""
import argparse,hashlib,json,zipfile
from pathlib import Path

def sha(data):return hashlib.sha256(data).hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    root=a.report.resolve();verification=json.loads((root/'verification.json').read_text())
    assert verification['result']=='passed' and verification['knownLiveTokensFound']==verification['genericJwtPatternsFound']==0
    destination=root/'downloads/workbench-simple-1.4.21-bank-results.zip';temporary=destination.with_suffix('.building')
    excluded={'package-verification.json','public-download-verification.json','SHA256SUMS'};expected={}
    with zipfile.ZipFile(temporary,'w') as archive:
        for source in sorted(root.rglob('*')):
            if not source.is_file() or source in [destination,temporary] or source.name in excluded:continue
            data=source.read_bytes();name=source.relative_to(root).as_posix();expected[name]=sha(data)
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
            archive.writestr(info,data)
    temporary.replace(destination)
    with zipfile.ZipFile(destination) as archive:
        assert archive.testzip() is None
        assert set(archive.namelist())==set(expected)
        assert all(sha(archive.read(name))==digest for name,digest in expected.items())
    result={'result':'passed','entries':len(expected),'path':str(destination.relative_to(Path.cwd())),
            'bytes':destination.stat().st_size,'sha256':sha(destination.read_bytes()),'zipCRCVerified':True,
            'everyArchivedFileMatchesFrozenPublicReport':True,'runtimePrivateRawDatabaseTokensExcluded':True,'sources':expected}
    (root/'package-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    artifacts=[destination,root/'downloads/workbench-simple-1.4.21-bank-site.zip']
    (root/'downloads/SHA256SUMS').write_text(''.join(sha(f.read_bytes())+'  '+f.name+'\n' for f in artifacts))
    print(json.dumps({k:v for k,v in result.items() if k!='sources'},ensure_ascii=False))
if __name__=='__main__':main()
