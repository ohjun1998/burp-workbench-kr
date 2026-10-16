"""Read the component release version from its runtime identity."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
match = re.search(r'public static final String VERSION\s*=\s*"(\d+\.\d+\.\d+)";',
                  (ROOT / 'src/main/java/kr/featuremapper/BuildInfo.java').read_text(encoding='utf-8'))
if not match:
    raise ValueError('BuildInfo.VERSION must contain a semantic release version')
VERSION = match.group(1)


def source_files():
    files = [path for folder in ['src', 'browser-helper', 'tests']
             for path in (ROOT / folder).rglob('*')
             if path.is_file() and '__pycache__' not in path.parts]
    files.extend(ROOT / name for name in [
        'build.py', 'release_info.py', 'README_KR.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md',
        'deps/montoya-api-2026.7.jar'])
    return sorted(files)
