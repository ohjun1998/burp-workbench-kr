"""Regression guards for received-but-unanalysed response evidence."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from summarize_live import coverage_summary


class LiveEvidenceTests(unittest.TestCase):
    def summary(self, *, page=True, response_status=200, page_status=200, complete=False, digest=''):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            report={'schemaVersion':2,'coverage':[{'key':'trace','originalId':'original-1',
                'state':'PARTIAL','source':'observed GET','responseStatus':response_status,
                'stages':['DISCOVERED','SCOPE_CHECKED','REQUESTED','RESPONDED']}]}
            with zipfile.ZipFile(root/'product-export','w') as z:
                z.writestr('report.json',json.dumps(report))
            data={'pages':[{'originalId':'original-1','status':page_status,
                'classification':'RESPONSE_PENDING_ANALYSIS','complete':complete,'digest':digest}] if page else []}
            return coverage_summary(root/'run.json',data)

    def test_pending_original_is_evidence_but_not_completed_analysis(self):
        result=self.summary()
        self.assertTrue(result['originalIdsResolve'])
        self.assertTrue(result['responseStatusMatchesOriginal'])
        self.assertTrue(result['pendingAnalysisRemainsPartial'])
        self.assertEqual((result['responded'],result['analyzed'],result['unconfirmed'],result['pendingAnalysisOriginals']),(1,0,1,1))

    def test_missing_received_original_keeps_gate_false(self):
        result=self.summary(page=False)
        self.assertFalse(result['originalIdsResolve'])
        self.assertFalse(result['responseStatusMatchesOriginal'])

    def test_status_uses_export_response_status_and_matches_original(self):
        self.assertFalse(self.summary(response_status=200,page_status=403)['responseStatusMatchesOriginal'])
        self.assertFalse(self.summary(response_status=None)['responseStatusMatchesOriginal'])

    def test_pending_response_is_not_promoted_or_given_a_fabricated_digest(self):
        self.assertFalse(self.summary(complete=True)['pendingAnalysisRemainsPartial'])
        self.assertFalse(self.summary(digest='uncomputed-digest')['pendingAnalysisRemainsPartial'])


if __name__=='__main__':
    unittest.main()
