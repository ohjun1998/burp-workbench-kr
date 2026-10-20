import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('packaging', Path(__file__).with_name('package_downloads.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)

class PackageChecks(unittest.TestCase):
    def test_existing_version_is_immutable(self):
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory)
            p.publish(dest, {'test.jar': b'first'}, [])
            p.verify(dest, [])
            p.publish(dest, {'test.jar': b'first'}, [])
            with self.assertRaises(RuntimeError):
                p.publish(dest, {'test.jar': b'second'}, [])
            self.assertEqual((dest / 'test.jar').read_bytes(), b'first')
            (dest / 'test.jar').write_bytes(b'tampered')
            with self.assertRaises(RuntimeError):
                p.verify(dest, [])

    def test_archive_is_reproducible(self):
        self.assertEqual(p.archive({'b': b'2', 'a': b'1'}), p.archive({'a': b'1', 'b': b'2'}))

    def test_unchanged_component_preserves_verified_release(self):
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / 'component'
            p.preserve_component(dest, {'test.jar': b'original-jdk'}, [])
            p.preserve_component(dest, {'test.jar': b'other-jdk'}, [])
            self.assertEqual((dest / 'test.jar').read_bytes(), b'original-jdk')
            (dest / 'test.jar').write_bytes(b'tampered')
            with self.assertRaises(RuntimeError):
                p.preserve_component(dest, {'test.jar': b'other-jdk'}, [])

    def test_docker_source_inputs_are_complete(self):
        names = {f.relative_to(p.LAB).as_posix() for f in p.docker_files()}
        for name in ['pom.xml', 'Dockerfile.source', 'compose.yaml', 'infra/nginx.conf', 'infra/HealthCheck.java', 'protocols/Dockerfile', 'protocols/requirements.txt', 'protocols/run.py', 'start.sh', 'start.ps1']:
            self.assertIn(name, names)
        self.assertTrue(any(n.startswith('src/main/java/') for n in names))
        self.assertTrue(any(n.startswith('src/main/webapp/') for n in names))
        self.assertNotIn('dist/ROOT.war', names)
        self.assertFalse(any('/runtime/' in n for n in names))

    def test_compose_builds_current_source(self):
        compose = (p.LAB / 'compose.yaml').read_text()
        self.assertIn('dockerfile: Dockerfile.source', compose)
        self.assertIn('image: enterprise-lab-was:' + p.lab_version(), compose)
        self.assertIn('BOUNTY_LAB_3.5_KR.md', {f.name for f in p.docker_files()})

if __name__ == '__main__':
    unittest.main()
