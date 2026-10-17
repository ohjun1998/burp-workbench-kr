"""Regression for fresh-release version selection and immutable historical downloads."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fresh_release', Path(__file__).resolve().parents[1] / 'audit/durability/run_release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class FreshReleaseChecks(unittest.TestCase):
    def run_fixture(self, payload):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pom = root / 'project/burp-development-workspace/community-security-lab/pom.xml'
            pom.parent.mkdir(parents=True)
            pom.write_text('<artifactId>loop-community-lab</artifactId><version>3.6.0</version>')
            downloads = root / 'downloads'
            current = downloads / 'docker/3.6.0/community-security-lab-3.6.0-docker.zip'
            current.parent.mkdir(parents=True)
            current.write_bytes(b'verified-current')
            historical = downloads / 'docker/3.5.2/community-security-lab-3.5.2-docker.zip'
            historical.parent.mkdir(parents=True)
            historical.write_bytes(b'immutable-historical')
            summary = root / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench/build/check-summary.json'
            summary.parent.mkdir(parents=True)
            summary.write_text(json.dumps(dict(version='0.37.0', regressionGroups=59, regressionChecks=1943, payloadHttpChecks=132, allFeaturesHttpChecks=101, bountyHttpChecks=266, totalChecks=2442)))

            def fresh_build(command, **kwargs):
                if command[-1] == 'release':
                    self.assertFalse(historical.exists(), 'Fresh output must not read a retained old archive')
                    current.parent.mkdir(parents=True)
                    current.write_bytes(payload)

            with patch.object(release, 'ROOT', root), patch.object(release.subprocess, 'run', side_effect=fresh_build):
                if payload != b'verified-current':
                    with self.assertRaisesRegex(AssertionError, '3.6.0'):
                        release.main()
                else:
                    release.main()
                    result = json.loads((root / 'tmp/durability/release.json').read_text())
                    self.assertEqual(result['dockerVersion'], '3.6.0')
                    self.assertTrue(result['freshDockerZipMatchesCommitted'])
            self.assertEqual(current.read_bytes(), b'verified-current')
            self.assertEqual(historical.read_bytes(), b'immutable-historical')

    def test_current_version_and_history_preserved(self):
        self.run_fixture(b'verified-current')

    def test_mismatched_fresh_archive_restores_all_committed_downloads(self):
        self.run_fixture(b'unexpected-fresh-output')


if __name__ == '__main__':
    unittest.main()
