from pathlib import Path
import argparse, hashlib, json

ROOT=Path(__file__).resolve().parent
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
parser=argparse.ArgumentParser();parser.add_argument('round',type=int,choices=range(1,6));args=parser.parse_args()
state_path=ROOT/'staging.json';state=json.loads(state_path.read_text())
if args.round!=state['nextRound']: raise SystemExit('Rounds must be applied in order, once each')
folder=ROOT/'changes'/f'round-{args.round}';change=json.loads((folder/'manifest.json').read_text())
for name,entry in change['files'].items():
 if sha(ROOT/'staging'/name)!=entry['beforeSHA256']: raise SystemExit('Unexpected pre-change source: '+name)
 if sha(folder/name)!=entry['afterSHA256']: raise SystemExit('Prepared change differs: '+name)
for name in change['files']:
 destination=ROOT/'staging'/name;destination.write_bytes((folder/name).read_bytes())
state['nextRound']=args.round+1
state['lastAppliedRound']=args.round
state_path.write_text(json.dumps(state,indent=2)+'\n')
print(json.dumps({'appliedRound':args.round,'changedFiles':list(change['files'])}))
