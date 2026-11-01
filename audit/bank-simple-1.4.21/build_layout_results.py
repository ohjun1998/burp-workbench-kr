#!/usr/bin/env python3
"""Render recorded bank results in the Workbench layout without sending any requests."""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path
import zipfile

HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[1]
EXPECTED_JAR_SHA = "9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c"
HTML_NAME = "Workbench_Simple_1.4.21_Results_Layout_KR.html"
ZIP_NAME = "Workbench_Simple_1.4.21_Results_Layout_KR.zip"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    report = args.report.resolve()
    output_directory = report / "layout-view"
    html_path = output_directory / HTML_NAME
    zip_path = output_directory / ZIP_NAME
    manifest_path = output_directory / "manifest.json"
    if any(path.exists() for path in (html_path, zip_path, manifest_path)):
        raise RuntimeError("Preserve existing generated layout files before creating a new version.")

    source_hashes: dict[str, str] = {}
    source_sizes: dict[str, int] = {}

    def read(path: Path) -> bytes:
        data = path.read_bytes()
        resolved = path.resolve()
        name = resolved.relative_to(REPOSITORY).as_posix() if resolved.is_relative_to(REPOSITORY) else str(resolved)
        source_hashes[name] = sha(data)
        source_sizes[name] = len(data)
        return data

    def load(name: str):
        return json.loads(read(report / name))

    verified = load("verification.json")
    if verified.get("result") != "passed" or verified.get("jarSha256") != EXPECTED_JAR_SHA:
        raise RuntimeError("Use the previously verified immutable Simple1.4.21 bank results.")
    core = load("results/results.json")
    if core.get("failedChecks") != 0 or len(core.get("checks", [])) != 13:
        raise RuntimeError("Expected the completed13-scenario bank run.")
    if core.get("jarSHA256") != EXPECTED_JAR_SHA or core.get("syntheticTargetResponses") is not False:
        raise RuntimeError("The recorded engine identity or target provenance differs.")

    data = {
        "traffic": load("results/transport-index.json"),
        "packets": load("layout-view/safe-packets.json"),
        "mapper": load("results/mapper-final.json"),
        "auth": load("results/auth-comparisons.json"),
        "js": load("results/js-analysis-final.json"),
        "environment": load("results/environment-final.json"),
        "paths": load("results/path-results.json"),
        "response": load("results/response-findings.json"),
        "payload": load("results/payload-sql.json"),
        "workflow": load("results/workflow.json"),
        "business": load("results/business/business-negative-transfer.json"),
        "businessTraffic": load("results/business/transport-index.json"),
        "evidence": load("results/evidence.json"),
        "data": load("results/structured-comparison.json"),
        "jwt": load("results/jwt-analysis.json"),
        "activity": load("results/activity-final.json"),
        "scope": load("results/scope-inspection.json"),
        "dashboard": load("results/dashboard.json"),
        "exposure": load("results/exposure-research-final.json"),
        "originals": load("results/original-id-index.json"),
        "diagnostics": load("results/diagnostics.json"),
    }
    frozen_sources = load("package-verification.json")["sources"]
    for name, expected in frozen_sources.items():
        path = (report / name).resolve()
        source_name = path.relative_to(REPOSITORY).as_posix() if path.is_relative_to(REPOSITORY) else str(path)
        if source_name in source_hashes and source_hashes[source_name] != expected:
            raise RuntimeError("A recorded input differs from the frozen public report: " + name)
    if len(data["traffic"]) != core["httpRequests"] or len(data["traffic"]) != 89:
        raise RuntimeError("The recorded core transport count differs.")
    if len(data["businessTraffic"]) != 5:
        raise RuntimeError("The separately recorded business workflow count differs.")
    packets = data["packets"]
    if not isinstance(packets, dict) or not isinstance(packets.get("core"), list) or not isinstance(packets.get("business"), list):
        raise RuntimeError("Expected redacted core and business packet arrays.")
    if len(packets["core"]) != 89 or len(packets["business"]) != 5:
        raise RuntimeError("The redacted packet rows do not cover the recorded transport.")

    # Project the same product groups as ScopeInspectionDashboard.belongs(). The
    # public export omits fingerprint/offset, so JSON equality must not deduplicate
    # findings that were distinct records in the original product snapshot.
    categories = Counter(row["category"] for row in data["scope"]["findings"])
    pages = data["scope"]["pages"]
    unique_page_urls = {row["url"] for row in pages}
    if len(unique_page_urls) != len(pages):
        raise RuntimeError("This public page export needs method-aware projection before counting dashboard paths.")
    data["dashboardMetrics"] = [
        {"key": "paths", "label": "관측 경로", "count": len(pages)},
        {"key": "api", "label": "API·명세", "count": categories["API"] + categories["INVENTORY"]},
        {"key": "environment", "label": "환경·DB 단서", "count": categories["ENVIRONMENT"] + categories["DATABASE"]},
        {"key": "secret", "label": "키·민감정보 후보", "count": categories["SECRET"]},
        {"key": "access", "label": "계정·권한 검토", "count": sum(categories[key] for key in ("ACCESS", "ACCOUNT", "PERMISSION"))},
        {"key": "coverage", "label": "미확인·제한", "count": categories["COVERAGE"]},
    ]

    template_path = HERE / "layout-results-template.html.in"
    css_path = HERE / "layout-results.css"
    font_path = REPOSITORY / "simple/burp-workbench/src/main/resources/fonts/WorkbenchSans-Regular.otf"
    # Workbench Sans is the renamed Noto/Adobe font. OFL.txt in this directory
    # concerns the separately bundled Nanum font; retain the matching Noto notice.
    license_path = font_path.with_name("NOTO-LICENSE.txt")
    template = read(template_path).decode("utf-8")
    css = read(css_path).decode("utf-8")
    font = read(font_path)
    data["fontLicense"] = read(license_path).decode("utf-8")
    read(Path(__file__))
    data["metadata"] = {
        "version": core["version"],
        "baseUrl": core["site"],
        "jarSha256": core["jarSHA256"],
        "coreHttpRequests": core["httpRequests"],
        "readOnly": True,
        "newBankRequests": 0,
        "nativeBurpGui": core["nativeBurp"],
        "sourceHashes": source_hashes,
    }
    for marker in ("__LAYOUT_DATA__", "__LAYOUT_CSS__"):
        if template.count(marker) != 1:
            raise RuntimeError("Expected exactly one template marker: " + marker)
    text = template.replace("__LAYOUT_CSS__", css)
    if text.count("__FONT_BASE64__") != 1:
        raise RuntimeError("Expected one embedded Workbench font marker after CSS insertion.")
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    payload = payload.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    text = text.replace("__LAYOUT_DATA__", payload).replace("__FONT_BASE64__", base64.b64encode(font).decode("ascii"))
    if any(marker in text for marker in ("__LAYOUT_DATA__", "__LAYOUT_CSS__", "__FONT_BASE64__")):
        raise RuntimeError("Unresolved layout template marker.")
    text = "\n".join(line.rstrip() for line in text.splitlines()) + "\n"
    html_data = text.encode("utf-8")
    output_directory.mkdir(parents=True, exist_ok=True)
    html_path.write_bytes(html_data)
    opening = (
        "실행 결과 HTML 열기\n\n"
        "1. ZIP을 압축 해제합니다.\n"
        f"2. {HTML_NAME}을 더블클릭해 브라우저로 엽니다.\n\n"
        "이 파일은 Workbench Simple1.4.21의 저장된 은행 실행 결과를 도구별 배치로 보여 줍니다.\n"
        "새 점검·요청 전송 기능은 없으며 네트워크 연결이나 별도 이미지 폴더가 필요하지 않습니다.\n"
        "후보·관측·미확인·미실행 표시는 원래 결과를 유지합니다. 모든 금액은 실습용 달러($)입니다.\n"
        "내장 Workbench Sans의 라이선스 고지는 HTML 데이터에 포함됩니다.\n"
    ).encode("utf-8-sig")
    with zipfile.ZipFile(zip_path, "w") as archive:
        for name, body in ((HTML_NAME, html_data), ("먼저_읽기.txt", opening)):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, body)
    with zipfile.ZipFile(zip_path) as archive:
        if archive.testzip() is not None or archive.read(HTML_NAME) != html_data or len(archive.namelist()) != 2:
            raise RuntimeError("ZIP integrity or embedded HTML identity differs.")
    manifest = {
        "format": "workbench-layout-recorded-results",
        "version": "1.4.21",
        "sourceCoreJarSha256": EXPECTED_JAR_SHA,
        "sourceHashes": source_hashes,
        "sourceBytes": source_sizes,
        "html": {"filename": HTML_NAME, "bytes": len(html_data), "sha256": sha(html_data)},
        "zip": {"filename": ZIP_NAME, "bytes": zip_path.stat().st_size, "sha256": sha(zip_path.read_bytes()),
                "entries": 2, "crcVerified": True, "containedHtmlByteIdentical": True},
        "font": {"family": "Workbench Sans", "bytes": len(font), "sha256": sha(font),
                 "licenseSource": str(license_path.relative_to(REPOSITORY))},
        "metrics": {"toolPages": 13, "coreToolPages": 8, "coreNetworkRequests": len(data["traffic"]),
                    "businessNetworkRequests": len(data["businessTraffic"]),
                    "mapperObservations": data["mapper"]["packets"], "mapperGroups": len(data["mapper"]["metadata"]["features"]),
                    "dashboardCards": data["dashboardMetrics"], "scopeFindingRows": len(data["scope"]["findings"])},
        "newBankRequests": 0,
        "readOnlyResults": True,
        "screenshotsUsedAsResultLayout": False,
        "externalRuntimeAssetsRequired": False,
        "validationScope": "Input identity, source/font hashes, template assembly and ZIP integrity; browser behavior is checked separately.",
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"html": manifest["html"], "zip": manifest["zip"], "sourceFiles": len(source_hashes),
                      "toolPages": 13, "newBankRequests": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
