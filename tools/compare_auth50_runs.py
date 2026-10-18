#!/usr/bin/env python3
"""Join independently verified Auth50 account cells without conflating settings."""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path

KEY = ('setting', 'profile', 'mode', 'featureId', 'actor')


def load(folder):
    with (folder / 'account-results.csv').open(encoding='utf-8-sig', newline='') as f:
        rows = list(csv.DictReader(f))
    indexed = {tuple(r[k] for k in KEY): r for r in rows}
    assert len(indexed) == len(rows) == 4200
    return indexed, json.loads((folder / 'analysis.json').read_text())


def compare(before_dir, after_dir, out):
    before, old = load(before_dir); after, new = load(after_dir)
    assert before.keys() == after.keys()
    changed = []; movements = Counter()
    for key, row in after.items():
        prior = before[key]
        for field in ('method', 'expectedPolicy', 'status', 'groundTruthViolation'):
            assert row[field] == prior[field], ('unmatched ground truth', key, field)
        movements[(row['setting'], prior['verdict'], row['verdict'], prior['finding'], row['finding'])] += 1
        if (prior['verdict'], prior['finding'], prior['classification']) != (row['verdict'], row['finding'], row['classification']):
            changed.append({**{k: row[k] for k in KEY}, 'name': row['name'], 'status': row['status'],
                            'oldClassification': prior['classification'], 'newClassification': row['classification'],
                            'oldFinding': prior['finding'], 'newFinding': row['finding'],
                            'oldVerdict': prior['verdict'], 'newVerdict': row['verdict'],
                            'oldReason': prior['reason'], 'newReason': row['reason'],
                            'classificationReason': row['classificationReason'], 'reviewReason': row['reviewReason']})
    out.mkdir(parents=True, exist_ok=True)
    if changed:
        with (out / 'changed-results.csv').open('w', encoding='utf-8-sig', newline='') as f:
            w = csv.DictWriter(f, fieldnames=list(changed[0]));w.writeheader();w.writerows(changed)
    result = dict(baselineVersion='0.43.0', currentVersion='0.44.0', matchedAccountCells=len(after),
                  sameMethodsExpectedPoliciesStatusesAndGroundTruth=True,
                  baselineMetrics=old['metrics'], currentMetrics=new['metrics'],
                  configuredReviewReduction=old['metrics']['configured']['account']['reviewCount']-new['metrics']['configured']['account']['reviewCount'],
                  migrations=[dict(setting=k[0], oldVerdict=k[1], newVerdict=k[2], oldFinding=k[3], newFinding=k[4], count=v) for k,v in sorted(movements.items())],
                  inputHashes=dict(before=old['inputsSha256'], after=new['inputsSha256']),
                  interpretation='Matched repeated fixture measurements; not independent production accuracy probabilities. REVIEW overlaps TN/FN.')
    (out / 'comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: result[k] for k in ('matchedAccountCells', 'sameMethodsExpectedPoliciesStatusesAndGroundTruth', 'configuredReviewReduction')}, ensure_ascii=False))


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before-dir',type=Path,required=True);p.add_argument('--after-dir',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();compare(a.before_dir,a.after_dir,a.out)
