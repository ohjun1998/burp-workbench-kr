#!/usr/bin/env python3
"""Expose only these two isolated Docker lab apps on executor loopback."""
import json
import select
import signal
import socket
import socketserver
import subprocess
import threading

DOCKER = ['env', '-u', 'DOCKER_HOST', '-u', 'DOCKER_CONTEXT', '-u', 'DOCKER_TLS',
          '-u', 'DOCKER_TLS_VERIFY', '-u', 'DOCKER_CERT_PATH', 'docker',
          '--host=unix:///var/run/docker.sock']
MAPPINGS = [(24801, 'simple-benchmarkjava', 8080), (24803, 'simple-vulnerableapp', 9090)]


class LoopbackServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 32


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection(self.server.upstream, timeout=15) as remote:
                pair = [self.request, remote]
                while True:
                    ready, _, _ = select.select(pair, [], [], 60)
                    if not ready:
                        return
                    for source in ready:
                        data = source.recv(65536)
                        if not data:
                            return
                        (remote if source is self.request else self.request).sendall(data)
        except (OSError, TimeoutError):
            return


servers = []
for port, name, upstream_port in MAPPINGS:
    config = json.loads(subprocess.check_output(DOCKER + ['inspect', name], text=True))[0]
    address = config['NetworkSettings']['Networks']['workbench-java-sites']['IPAddress']
    server = LoopbackServer(('127.0.0.1', port), Relay)
    server.upstream = (address, upstream_port)
    servers.append(server)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print(json.dumps({'bind': '127.0.0.1', 'port': port, 'container': name, 'upstreamPort': upstream_port}), flush=True)

stopped = threading.Event()
signal.signal(signal.SIGTERM, lambda *_: stopped.set())
signal.signal(signal.SIGINT, lambda *_: stopped.set())
stopped.wait()
for server in servers:
    server.shutdown()
    server.server_close()
