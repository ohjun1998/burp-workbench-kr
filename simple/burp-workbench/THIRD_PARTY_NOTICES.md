# Third-party notices / Burp Workbench KR 0.2.0

The launcher code is AGPL-3.0. Upstream components retain their original licenses and copyrights. Nested engine binaries are unmodified official BApp Store distributions. API libraries are provided by Burp and are not bundled.

| Component | Version | License | Distribution |
|---|---|---|---|
|Autorize|1.9.4|NOT-CONFIRMED|Downloaded directly by end user; NOT bundled|
|Auth Analyzer|1.1.14|MIT|Bundled unmodified engine|

auth-analyzer corresponding upstream source: https://github.com/PortSwigger/auth-analyzer/tree/51225b860d1406e0c229154e73960ed6aee986b0
Source archive SHA-256: 0aafe69a0f6ec6781b2ee0ca27d1c4ca8c961f86978b245a49c86d119405fe49
|Logger++|3.20.0|AGPL-3.0|Bundled unmodified engine|

logger-plus-plus corresponding upstream source: https://github.com/PortSwigger/logger-plus-plus/tree/6fd5f21b714bb3aef2b34d709b0d05b8ade15d2c
Source archive SHA-256: f0a7dba8697eac41db574505fc659b03964f02f41437a87f6ab94e5236592364
|Hackvertor|2.2.67|NOT-CONFIRMED|Downloaded directly by end user; NOT bundled|
|Param Miner|1.59|Apache-2.0|Bundled unmodified engine|

param-miner corresponding upstream source: https://github.com/PortSwigger/param-miner/tree/e43e3b16be11117ed67cfde60e80acf2e95a4adc
Source archive SHA-256: 7e82e0f7a39825314e670f029d46491ffbaca4544515837dc09b759847f605ed
|JWT Editor|2.6.2|Apache-2.0|Bundled unmodified engine|

jwt-editor corresponding upstream source: https://github.com/PortSwigger/jwt-editor/tree/bde3e4d5cd767b9003b8f4c0bea164c84c5a6009
Source archive SHA-256: a7ed3fc30972a381d3e17b83921eaa82514ff2efabe7a29dbdf4e6fb94ebb424
|Turbo Intruder|2.0.1|AGPL-3.0|Bundled unmodified engine|

turbo-intruder corresponding upstream source: https://github.com/PortSwigger/turbo-intruder/tree/7b9d1f9db1a268aed8d62a3415e9fd52c1cecf47
Source archive SHA-256: 73e7c7bd08d8fb898a73640febbbc4b594b65dbadac0e8bc1019c4d0a16d666e
|JS Miner|1.16|Apache-2.0|Bundled unmodified engine|

js-miner corresponding upstream source: https://github.com/PortSwigger/js-miner/tree/420c5664e2b0b5a0278a993ac00cf0c9d3907a52
Source archive SHA-256: b5839cadceb651b25ea1a6691902ab6f6916f7394a7ce299270799263a39178d
|HTTP Request Smuggler|3.1.0|Apache-2.0|Bundled unmodified engine|

http-request-smuggler corresponding upstream source: https://github.com/PortSwigger/http-request-smuggler/tree/3ecb1afa6a1d21ef1ec4e6eb1337818b53a2b4c0
Source archive SHA-256: a4f0abb59f0ad20424851ab4ce21302cefb4a3b5d3068a552f7e8b3a1cbe658e

Jython standalone 2.7.4 is bundled for Python engine execution. Its included LICENSE text and third-party notices are preserved below and inside the nested JAR.

Autorize and Hackvertor: no explicit redistribution grant was found in the inspected repositories; no upstream source, image or binary from these projects is redistributed in this package. The end user obtains the original signed .bapp archive directly from the official store; checksums are pinned.

Third-party dependencies inside upstream fat JARs retain their respective licenses. The original JAR META-INF/license resources have not been removed. Source archives and upstream build scripts for the distributed engines accompany the launcher source archive.

The launcher modifies no engine source. It changes the host integration: isolated class loaders, namespaced settings, grouped menus, mounted tabs, per-engine output and unload tracking.
## Rhino JavaScript parser 1.9.1

Copyright Mozilla contributors. MPL-2.0 and upstream file-specific notices; see `licenses/rhino/LICENSE.txt`. Only static parser/AST APIs are used; JavaScript is never evaluated. Rhino packages are relocated to `kr.workbench.shaded.rhino` and `kr.workbench.shaded.classfile` by the included `shade_rhino.py` to avoid host collisions.

Original source: https://github.com/mozilla/rhino/tree/Rhino1_9_1_Release and `upstream/rhino-1.9.1-source.zip` (Maven source artifact), SHA-256 `026dfb1162c0f066624fe1c89c8670d0bddb4680d1e88aab9d290b96856d3c37`. Original binary SHA-256 `12dbb224cea124a5d32968722e8eb161c967bf15f3c9a795d1b05592b1147975`. Relocation is reproducible with the source distribution's build script.

jsluice inspired the analysis workflow but its code/binary is not included. This implementation is not an official jsluice port.

## Payload Runner KR 1.3.0

동일 프로젝트의 자체 구현을 소스로 통합했습니다. Engine/Adaptive 알고리즘과 프로필을 보존하고 확장 진입점은 PayloadRunnerPanel로 바꿨습니다. 라이선스 원문: `licenses/payload-runner-LICENSE`. Java 21에서 콜백 종료 시 진행 중인 연결도 닫고 수집 스레드 종료를 기다리도록 보완했습니다.

## Workbench KR 0.16.0 resources

- NanumGothic Regular is bundled unmodified as a Korean glyph fallback. Copyright NHN Corporation, design by Sandoll Communications. SIL Open Font License 1.1; full notice in `fonts/OFL.txt`. Reference: https://github.com/google/fonts/tree/main/ofl/nanumgothic
- Environment paths are independently curated facts inspired by SecLists, Assetnote, official product documentation and the Wiz Actuator research. No complete upstream wordlist, scanner or exploit template is bundled. Reference details: `ENVIRONMENT_SOURCES_KR.md`. Imported user wordlists retain their upstream licensing obligations.

## Google Brotli Java decoder 0.1.2

The MIT-licensed org.brotli:dec:0.1.2 decoder is bundled under kr.workbench.shaded.brotli to avoid host collisions. The license is preserved in licenses/brotli/LICENSE. Upstream: https://github.com/google/brotli . Maven SHA-256: 615c0c3efef990d77831104475fba6a1f7971388691d4bad1471ad84101f6d52 . No external process or native decoder is required.
