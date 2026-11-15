#!/bin/sh
set -eu
cd "$(dirname "$0")"
if command -v java >/dev/null 2>&1; then
  WB_JAVA=$(command -v java)
elif [ -x "$HOME/BurpSuite/jre/bin/java" ]; then
  WB_JAVA="$HOME/BurpSuite/jre/bin/java"
else
  echo 'JDK 17 이상이 필요합니다. Java 경로를 PATH에 추가하세요.' >&2
  exit 1
fi
WB_JAVAC="$(dirname "$WB_JAVA")/javac"
if [ ! -x "$WB_JAVAC" ]; then
  echo 'Java 컴파일러 javac가 필요합니다.' >&2
  exit 1
fi
case "${1:-doctor}" in
  llm-test) shift; exec python3 tools/check_local_llm.py --java "$WB_JAVA" --javac "$WB_JAVAC" "$@" ;;
  doctor|build|test|check|release) exec python3 dev.py "${1:-doctor}" --java "$WB_JAVA" --javac "$WB_JAVAC" ;;
  *) echo '사용법: sh run-local.sh doctor|build|test|check|release|llm-test [--no-http]' >&2; exit 2 ;;
esac
