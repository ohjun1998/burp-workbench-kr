#!/usr/bin/env bash
set -euo pipefail
cd /workspace/burp-workbench-kr
# Reuse the existing checkout; never create a Git worktree for onboarding.
jdk_dir=/workspace/tools/burp-workbench/jdk-17.0.21+9
if [[ ! -x "$jdk_dir/bin/java" || ! -f "$jdk_dir/lib/ct.sym" ]]; then
  bash /workspace/tools/burp-workbench/install.sh
fi
"$jdk_dir/bin/java" -version
python3 --version
node --version
python3 audit/bank-simple-1.4.21/site/bank_site.py prepare --root /workspace/tools/burp-workbench/bank-simple-1.4.21
