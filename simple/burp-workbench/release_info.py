"""Read the runtime release identity without executing the build."""
from pathlib import Path
import re


def read_version(root=None):
    root = Path(root) if root is not None else Path(__file__).resolve().parent
    text = (root / 'src/main/java/kr/workbench/BuildInfo.java').read_text(encoding='utf-8')
    match = re.search(r'public static final String VERSION\s*=\s*"(\d+\.\d+\.\d+)";', text)
    if not match:
        raise ValueError('BuildInfo.VERSION must contain an explicit semantic release version')
    return match.group(1)


VERSION = read_version()


def read_identity(name):
    """Keep build and runtime branding in one source of truth."""
    text = (Path(__file__).resolve().parent / 'src/main/java/kr/workbench/BuildInfo.java').read_text(encoding='utf-8')
    match = re.search(r'public static final String ' + re.escape(name) + r'\s*=\s*"([^"\r\n]+)";', text)
    if not match:
        raise ValueError('Missing explicit BuildInfo.' + name)
    return match.group(1)


PRODUCT_NAME = read_identity('PRODUCT_NAME')
IMPLEMENTATION_TITLE = read_identity('IMPLEMENTATION_TITLE')
ARTIFACT_ID = read_identity('ARTIFACT_ID')
EDITION = 'strix'
