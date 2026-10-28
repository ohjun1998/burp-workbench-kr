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
