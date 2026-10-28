#!/bin/bash
set -eu
java -Xmx128m -cp '/app/WEB-INF/lib/*' org.hsqldb.Server --address 127.0.0.1 --database.0 file:/runtime/db/benchmark --dbname.0 benchmarkDataBase > /runtime/hsqldb.log 2>&1 &
echo "$!" > /runtime/hsqldb.pid
for attempt in {1..100}; do
  if (echo > /dev/tcp/127.0.0.1/9001) 2>/dev/null; then break; fi
  sleep 0.2
done
java -Xmx256m -cp '/usr/local/tomcat/lib/*:/app/WEB-INF/lib/*:/app/WEB-INF/classes' org.owasp.benchmark.helpers.LDAPServer > /runtime/ldap.log 2>&1 &
echo "$!" > /runtime/ldap.pid
export JAVA_OPTS='-Xmx1024m --add-opens=java.base/java.lang=ALL-UNNAMED -Dorg.owasp.esapi.resources=/app/WEB-INF/classes'
exec /usr/local/tomcat/bin/catalina.sh run
