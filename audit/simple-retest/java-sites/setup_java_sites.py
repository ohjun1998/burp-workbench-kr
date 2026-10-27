#!/usr/bin/env python3
"""Prepare two pinned, unmodified Java lab apps outside the Workbench checkout.

Requires Python 3.12+, Docker at the managed local socket, Linux amd64, and
HTTPS access for the pinned images, source archives and build dependencies.
The two running apps have no public egress and are exposed on loopback only.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import socket
import subprocess
import sys
import tarfile
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

MAVEN_IMAGE = 'maven@sha256:e4a7ace3dc0d645ed97f8d9ad0b0d3f0b14fa8d150138f27f116d7105a639b82'
TOMCAT_IMAGE = 'tomcat@sha256:baafc39d5598aac501d7b5e0d25ea0db46c1736fe598b2232e76c3de4ddbacac'
NETWORK = 'workbench-java-sites'
TARGETS = {
    'benchmarkjava': {
        'name': 'BenchmarkJava', 'repository': 'OWASP-Benchmark/BenchmarkJava',
        'commit': 'bc5ffeb09fb18ab039806d84ca1f718c2e332c54',
        'archiveSha256': '85ea734eee07e7a2cadfb89094720df1facb12cf0ee890f334d21b7584edbfee',
        'port': 24801, 'context': '/benchmark/',
        'direct': '/benchmark/pathtraver-00/BenchmarkTest00001',
        'artifact': 'target/benchmark.war',
    },
    'vulnerableapp': {
        'name': 'VulnerableApp', 'repository': 'SasanLabs/VulnerableApp',
        'commit': '668ab14704b63d286bc73f5ec302885a5f192c2e',
        'archiveSha256': '4f0f65c711a1028434973386cb2cadb9c58c7a79ab9364848b4f1a832ef46862',
        'port': 24803, 'context': '/VulnerableApp/', 'direct': '/VulnerableApp/scanner',
        'artifact': 'build/libs/VulnerableApp-1.0.0.jar',
    },
}
DOCKER = ['docker', '--host=unix:///var/run/docker.sock']
DOCKER_ENV = {key: value for key, value in os.environ.items() if key not in {
    'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'}}


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def docker(*args: str, capture=False) -> str:
    result = subprocess.run(DOCKER + list(args), env=DOCKER_ENV, check=True,
                            stdout=subprocess.PIPE if capture else subprocess.DEVNULL, text=True)
    return result.stdout.strip() if capture else ''


def exists(kind: str, name: str) -> bool:
    return subprocess.run(DOCKER + [kind, 'inspect', name], env=DOCKER_ENV,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def source(root: Path, identifier: str, target: dict) -> None:
    archive = root / f'{identifier}-{target["commit"]}.tar.gz'
    if not archive.exists():
        temporary = archive.with_suffix('.downloading')
        url = f'https://codeload.github.com/{target["repository"]}/tar.gz/{target["commit"]}'
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open('xb') as output:
            total = 0
            while block := response.read(1024 * 1024):
                total += len(block)
                if total > 512 * 1024 * 1024:
                    raise RuntimeError('Source archive exceeds 512 MiB')
                output.write(block)
        temporary.replace(archive)
    if digest(archive) != target['archiveSha256']:
        raise RuntimeError('Pinned source archive SHA-256 differs: ' + identifier)
    destination = root / identifier
    with tarfile.open(archive) as package:
        entries = package.getmembers()
        top = entries[0].name.split('/')[0]
        if any(entry.name.split('/')[0] != top for entry in entries):
            raise RuntimeError('Unexpected source archive roots')
        if not destination.exists():
            temporary = root / '_extract'
            temporary.mkdir(exist_ok=True)
            package.extractall(temporary, filter='data')
            (temporary / top).rename(destination)
        for entry in entries:
            if entry.isfile():
                relative = Path(*Path(entry.name).parts[1:])
                if hashlib.sha256(package.extractfile(entry).read()).hexdigest() != digest(destination / relative):
                    raise RuntimeError('Original upstream file changed: ' + str(relative))


def tools(root: Path, ca_file: Path | None) -> dict:
    for image in (MAVEN_IMAGE, TOMCAT_IMAGE):
        if not exists('image', image):
            docker('pull', image)
    temporary_container = docker('create', MAVEN_IMAGE, capture=True)
    try:
        for original, name in [('/opt/java/openjdk', 'java17'), ('/usr/share/maven', 'maven')]:
            if not (root / name).exists():
                docker('cp', temporary_container + ':' + original, str(root / name))
    finally:
        docker('rm', temporary_container)
    runtime = root / 'java-runtime'
    runtime.mkdir(exist_ok=True)
    trust = runtime / 'cacerts'
    if not trust.exists():
        shutil.copy2(root / 'java17/lib/security/cacerts', trust)
        if ca_file:
            subprocess.run([str(root / 'java17/bin/keytool'), '-importcert', '-noprompt',
                            '-alias', 'workbench-session-proxy', '-file', str(ca_file),
                            '-keystore', str(trust), '-storepass', 'changeit'], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    proxy = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY', os.environ.get('HTTP_PROXY', '')))
    proxy_xml = ''
    properties = ['-Djavax.net.ssl.trustStore=' + str(trust)]
    if proxy.hostname:
        proxy_xml = '<proxies><proxy><id>managed-session</id><active>true</active><protocol>http</protocol><host>' + escape(proxy.hostname) + '</host><port>' + str(proxy.port or 80) + '</port><nonProxyHosts>localhost|127.*|[::1]</nonProxyHosts>'
        for scheme in ('http', 'https'):
            properties += [f'-D{scheme}.proxyHost={proxy.hostname}', f'-D{scheme}.proxyPort={proxy.port or 80}']
        for xml_key, java_key, value in [('username', 'proxyUser', proxy.username), ('password', 'proxyPassword', proxy.password)]:
            if value:
                decoded = urllib.parse.unquote(value)
                proxy_xml += '<' + xml_key + '>' + escape(decoded) + '</' + xml_key + '>'
                properties += [f'-D{scheme}.{java_key}={decoded}' for scheme in ('http', 'https')]
        proxy_xml += '</proxy></proxies>'
    settings = runtime / 'maven-settings.xml'
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">' + proxy_xml + '</settings>\n')
    settings.chmod(0o600)
    environment = dict(os.environ)
    environment['JAVA_HOME'] = str(root / 'java17')
    environment['PATH'] = str(root / 'java17/bin') + os.pathsep + environment.get('PATH', '')
    environment['MAVEN_OPTS'] = shlex.join(['-Xmx1536m'] + properties)
    environment['GRADLE_OPTS'] = shlex.join(properties)
    environment['GRADLE_USER_HOME'] = str(root / 'gradle-cache')
    return {'environment': environment, 'properties': properties, 'settings': settings}


def build(root: Path, identifier: str, target: dict, toolchain: dict) -> None:
    if (root / identifier / target['artifact']).is_file():
        return
    if identifier == 'benchmarkjava':
        command = [str(root / 'maven/bin/mvn'), '-B', '-ntp', '-s', str(toolchain['settings']),
                   '-Dmaven.repo.local=' + str(root / 'maven-cache'), '-DskipTests', '-Dspotless.skip=true', 'package']
    else:
        command = ['./gradlew', '--no-daemon', '--max-workers=2', '--console=plain',
                   '-Dorg.gradle.jvmargs=' + shlex.join(['-Xmx1024m'] + toolchain['properties']), 'bootJar', '-x', 'test']
    with (root / f'{identifier}-build.log').open('wb') as output:
        subprocess.run(command, cwd=root / identifier, env=toolchain['environment'],
                       stdout=output, stderr=subprocess.STDOUT, check=True)
    # Spotless is skipped deliberately: original source must remain byte-identical.
    source(root, identifier, target)


def start(root: Path) -> None:
    if not exists('network', NETWORK):
        docker('network', 'create', '--internal', NETWORK)
    network = json.loads(docker('network', 'inspect', NETWORK, capture=True))[0]
    if not network.get('Internal'):
        raise RuntimeError('Existing lab network is not internal; it was preserved')
    for name, image in [('benchmarkjava', TOMCAT_IMAGE), ('vulnerableapp', MAVEN_IMAGE)]:
        if exists('container', 'simple-' + name):
            current = json.loads(docker('inspect', 'simple-' + name, capture=True))[0]
            mounts = {entry['Destination']: entry['Source'] for entry in current['Mounts']}
            if current['Config']['Image'] != image or mounts.get('/runtime') != str(root / (name + '-runtime')):
                raise RuntimeError('Existing container belongs to another lab workspace; it was preserved')
    benchmark = root / 'benchmarkjava-runtime'
    benchmark.mkdir(exist_ok=True)
    if not (benchmark / 'app').exists():
        shutil.copytree(root / 'benchmarkjava/target/benchmark', benchmark / 'app')
    (benchmark / 'db').mkdir(exist_ok=True)
    server = ET.parse(root / 'benchmarkjava/src/config/local/server.xml')
    server.getroot().set('port', '-1')
    connector = server.getroot().find('./Service/Connector')
    connector.attrib.clear()
    connector.attrib.update(address='0.0.0.0', port='8080', protocol='HTTP/1.1', connectionTimeout='20000', URIEncoding='ISO-8859-1')
    server.write(benchmark / 'server.xml', encoding='utf-8', xml_declaration=True)
    shutil.copy2(Path(__file__).with_name('benchmark-entrypoint.sh'), benchmark / 'start.sh')
    hsql = next((benchmark / 'app/WEB-INF/lib').glob('hsqldb-*.jar'))
    mount = lambda original, destination, readonly=False: ['--mount', f'type=bind,src={original},dst={destination}' + (',readonly' if readonly else '')]
    if not exists('container', 'simple-benchmarkjava'):
        docker('run', '-d', '--name', 'simple-benchmarkjava', '--network', NETWORK,
               '--memory', '1536m', '--cpus', '2', '--workdir', '/runtime',
               *mount(benchmark, '/runtime'), *mount(benchmark / 'app', '/app'),
               *mount(benchmark / 'app', '/usr/local/tomcat/webapps/benchmark'),
               *mount(benchmark / 'server.xml', '/usr/local/tomcat/conf/server.xml', True),
               *mount(hsql, '/usr/local/tomcat/lib/' + hsql.name, True), TOMCAT_IMAGE, '/bin/bash', '/runtime/start.sh')
    else:
        docker('start', 'simple-benchmarkjava')
    vulnerable = root / 'vulnerableapp-runtime'
    vulnerable.mkdir(exist_ok=True)
    if not (vulnerable / 'app.jar').exists():
        shutil.copy2(root / 'vulnerableapp/build/libs/VulnerableApp-1.0.0.jar', vulnerable / 'app.jar')
    if not (vulnerable / 'scanner').exists():
        shutil.copytree(root / 'vulnerableapp/scanner', vulnerable / 'scanner')
    if not exists('container', 'simple-vulnerableapp'):
        docker('run', '-d', '--name', 'simple-vulnerableapp', '--network', NETWORK,
               '--memory', '1280m', '--cpus', '2', '--workdir', '/runtime',
               *mount(vulnerable, '/runtime'), MAVEN_IMAGE, 'java', '-Xmx768m', '-jar', '/runtime/app.jar',
               '--server.address=0.0.0.0', '--server.port=9090')
    else:
        docker('start', 'simple-vulnerableapp')
    if not all(listening(port) for port in (24801, 24803)):
        with (root / 'java-sites-loopback.log').open('ab') as output:
            process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('java-sites-loopback.py'))],
                                       stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        (root / 'java-sites-loopback.pid').write_text(str(process.pid) + '\n')


def listening(port: int) -> bool:
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=1):
            return True
    except OSError:
        return False


def health(root: Path) -> None:
    reports = []
    for identifier, target in TARGETS.items():
        checks = []
        for path in (target['context'], target['direct']):
            deadline = time.monotonic() + 90
            while True:
                try:
                    connection = http.client.HTTPConnection('127.0.0.1', target['port'], timeout=10)
                    connection.request('GET', path)
                    response = connection.getresponse()
                    body = response.read(4 * 1024 * 1024)
                    if response.status != 200:
                        raise RuntimeError('Health response was not HTTP 200')
                    checks.append({'path': path, 'status': response.status, 'bodySha256': hashlib.sha256(body).hexdigest()})
                    connection.close()
                    break
                except (OSError, http.client.HTTPException, RuntimeError):
                    if time.monotonic() > deadline:
                        raise RuntimeError('Local application did not become ready: ' + identifier) from None
                    time.sleep(.5)
        reports.append({'siteId': identifier, 'name': target['name'], 'sourceCommit': target['commit'],
                        'sourceArchiveSha256': target['archiveSha256'], 'baseURL': f'http://127.0.0.1:{target["port"]}',
                        'startURL': f'http://127.0.0.1:{target["port"]}' + target['context'],
                        'scopeURL': f'http://127.0.0.1:{target["port"]}' + target['context'],
                        'servletSubset': False, 'builtArtifactSha256': digest(root / identifier / target['artifact']),
                        'health': checks, 'globalLoginRequired': False})
    (root / 'java-sites-ready.json').write_text(json.dumps(reports, indent=2) + '\n')
    print(json.dumps({'status': 'ready', 'sites': [row['startURL'] for row in reports]}))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dest', type=Path, required=True, help='Absolute external lab workspace')
    parser.add_argument('--ca-file', type=Path, help='Optional managed HTTPS proxy CA; never disables TLS checks')
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.machine().lower() not in ('x86_64', 'amd64'):
        parser.error('This measured setup uses the pinned Linux amd64 image platforms')
    checkout = Path(__file__).resolve().parents[3]
    if not args.dest.is_absolute() or checkout == args.dest.resolve() or checkout in args.dest.resolve().parents:
        parser.error('--dest must be an absolute directory outside this checkout')
    args.dest.mkdir(parents=True, exist_ok=True)
    for identifier, target in TARGETS.items():
        source(args.dest, identifier, target)
    toolchain = tools(args.dest, args.ca_file.resolve() if args.ca_file else None)
    for identifier, target in TARGETS.items():
        build(args.dest, identifier, target, toolchain)
    start(args.dest)
    health(args.dest)


if __name__ == '__main__':
    main()
