#!/usr/bin/env python3
"""Build version-paired local Korean navigation retest reports from actual HTTP corpora.

Raw values stay in the explicitly selected output directory. This tool never
uploads reports, sends target requests, validates discovered credentials, or
reads the process environment. Source files must be below each recorded run.
"""
from __future__ import annotations

import argparse
import bisect
import collections
import csv
import datetime as dt
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[2]
BEFORE_VERSION = "1.3.1"
AFTER_VERSION = "1.3.2"
PDF_NAME = f"Workbench_Simple_{AFTER_VERSION}_Retest_KR.pdf"
FONT = ROOT / "simple/burp-workbench/src/main/resources/fonts/NanumGothic-Regular.ttf"
CATEGORIES = {
    "api": "API", "sensitive": "민감정보", "token": "Token", "key": "Key",
    "environment": "환경·서버", "database": "DB", "path": "경로", "internal": "내부 정보", "other": "기타 주요정보",
}
ORIGINS = {
    "productDetected": "제품 탐지", "productReportSupplement": "제품 보고서 보충 파싱",
    "reportSupplemental": "재시험 보고서 추가 파싱", "testLoginAuth": "테스트 로그인 인증값",
}
CLASSIFICATIONS = {"PUBLIC_IDENTIFIER": "공개 식별자", "EXAMPLE_OR_PLACEHOLDER": "예제·플레이스홀더", "UNVERIFIED": "용도·유효성 미검증", "DYNAMIC_UNRESOLVED": "동적값 미확정 · 템플릿 원문"}
SITE_NAMES = {
    "juice": "OWASP Juice Shop", "juice-shop": "OWASP Juice Shop", "juiceshop": "OWASP Juice Shop",
    "benchmark": "OWASP BenchmarkJava", "benchmarkjava": "OWASP BenchmarkJava", "vapp": "VulnerableApp", "vulnerableapp": "VulnerableApp",
    "oops": "OopsSec Store", "oopssec": "OopsSec Store", "dv": "DVBank Lab", "dvbank": "DVBank Lab",
    "bank": "VulnBank", "vulnbank": "VulnBank", "community": "LOOP Community", "loop": "LOOP Community",
}
SITE_ORDER = {name: index for index, name in enumerate(("juice-shop", "benchmarkjava", "vulnerableapp", "oopssec", "dvbank", "vulnbank", "loop"))}
TOKEN_NAME = re.compile(r"token|(?:^|[_-])jwt(?:$|[_-])|bearer|authorization|session(?:id|[_-]id)?$", re.I)
KEY_NAME = re.compile(r"(?:api|access|secret|private|public|stream|signing|client)[_-]?(?:key|secret)|secret$|client[_-]?id$|site[_-]?key$|measurement[_-]?id$", re.I)
SENSITIVE_NAME = re.compile(r"password|passwd|(?:^|[_-])pwd$|(?:^|[_-])ssn(?:$|[_-])|social.?security|credit.?card|cvv|email|phone|birth|dob$|address|street|zipcode|zip_code", re.I)
DB_NAME = re.compile(r"database|db(?:url|name|host|user|version)?$|jdbc|connection.?string|mongodb|postgres|sqlite|mysql|mariadb|redis|prisma", re.I)
ENV_NAME = re.compile(r"server|framework|version|runtime|node(?:version)?$|environment|platform|os(?:name|version)?$|debug|stack.?trace|powered.?by", re.I)
INTERNAL_NAME = re.compile(r"host(?:name)?|internal|file.?path|working.?dir|home.?dir|root.?dir|base.?dir", re.I)
OTHER_NAME = re.compile(r"flag|role|permission|username|fullname|full_name|balance|account|user[_-]?id|client[_-]?id", re.I)
API_VALUE = re.compile(r"^(?:https?://[^\s<>]+)?/(?:api(?:/|$)|rest(?:/|$)|graphql(?:/|$)|v[1-9][0-9]*/|swagger|openapi)", re.I)
PRIVATE_URL = re.compile(r"^(?:https?|jdbc|mongodb|postgres(?:ql)?|mysql|redis)://(?:[^/@\s]+@)?(?:localhost|127\.|10\.|192\.168\.|172\.(?:1[6-9]|2[0-9]|3[01])\.)", re.I)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def read_text_exact(path: Path):
    return path.read_bytes().decode("utf-8")


def write_json(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def bounded_path(root: Path, value: str) -> Path:
    path = (root / value).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError(f"Evidence path escapes recorded run: {value!r}")
    if not path.is_file():
        raise ValueError(f"Recorded evidence file is missing: {value!r}")
    return path


def category_for(name: str, value: str, pointer="") -> str | None:
    if re.fullmatch(r"bearer[_-]?format", name, re.I):
        return "other"
    if TOKEN_NAME.search(name):
        return "token"
    if KEY_NAME.search(name):
        return "key"
    if SENSITIVE_NAME.search(name) or name.endswith("SSN"):
        return "sensitive"
    if DB_NAME.search(name) or (PRIVATE_URL.search(value) and re.match(r"(?:jdbc|mongodb|postgres|mysql|redis)", value, re.I)):
        return "database"
    if ENV_NAME.search(name):
        return "environment"
    if API_VALUE.search(value) or pointer.startswith("/paths/"):
        return "api"
    if INTERNAL_NAME.search(name) or PRIVATE_URL.search(value):
        return "internal"
    if OTHER_NAME.search(name) or re.search(r"(?:OSS|FLAG|CTF)\{[^\r\n{}]+\}", value):
        return "other"
    return None


def json_leaves(text: str):
    """Yield parsed scalars with exact JSON lexical spans and JSON pointers."""
    json.loads(text)  # Reject invalid JSON before yielding any partial parse.
    decoder = json.JSONDecoder()
    size = len(text)

    def white(i):
        while i < size and text[i].isspace():
            i += 1
        return i

    def parse(i, path):
        i = white(i)
        if text[i] == "{":
            i = white(i + 1)
            while text[i] != "}":
                key, end = decoder.raw_decode(text, i)
                i = white(end)
                if text[i] != ":":
                    raise ValueError("Invalid JSON object separator")
                escaped = str(key).replace("~", "~0").replace("/", "~1")
                i = yield from parse(i + 1, path + [escaped])
                i = white(i)
                if text[i] == "}":
                    break
                if text[i] != ",":
                    raise ValueError("Invalid JSON member separator")
                i = white(i + 1)
            return i + 1
        if text[i] == "[":
            i, index = white(i + 1), 0
            while text[i] != "]":
                i = yield from parse(i, path + [str(index)])
                index += 1
                i = white(i)
                if text[i] == "]":
                    break
                if text[i] != ",":
                    raise ValueError("Invalid JSON array separator")
                i = white(i + 1)
            return i + 1
        value, end = decoder.raw_decode(text, i)
        yield {"pointer": "/" + "/".join(path), "name": path[-1] if path else "$",
               "parsed": value, "rawLexeme": text[i:end], "offset": i, "length": end - i}
        return end

    yield from parse(0, [])


def scalar_text(value):
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def source_record(run, record, area, text, offset, length, name="", pointer=""):
    cache = run.setdefault("_source_cache", {})
    cache_key = (record.get("index"), record.get("responseFile"), area)
    if cache_key in cache:
        base, newlines = cache[cache_key]
        source = dict(base)
        source.update({"locator": pointer or name, "line": bisect.bisect_left(newlines, offset) + 1,
                       "column": offset - (newlines[bisect.bisect_left(newlines, offset) - 1] if bisect.bisect_left(newlines, offset) else -1),
                       "offset": offset, "length": length})
        return source
    pages = [p for p in run.get("pages", []) if
             p.get("url") == record.get("url") and p.get("method", "GET") == record.get("method", "GET")
             and p.get("account") == record.get("account")]
    # Select an OriginalID only when the precise recorded body/response file is
    # shared, or its hash is equal. URL-only matching cannot establish identity.
    original_ids = []
    body_hash = sha(text.encode("utf-8"))
    actual_body_hash = sha(bounded_path(run["_directory"], record["decodedBodyFile"]).read_bytes()) if record.get("decodedBodyFile") else None
    for page in pages:
        same = page.get("decodedBodyFile") == record.get("decodedBodyFile")
        if not same and page.get("decodedBodyFile") and record.get("decodedBodyFile"):
            try:
                same = sha(bounded_path(run["_directory"], page["decodedBodyFile"]).read_bytes()) == actual_body_hash
            except (OSError, ValueError):
                same = False
        if same and page.get("originalId"):
            original_ids.append(page["originalId"])
    response_file = record.get("responseFile")
    response_hash = sha(bounded_path(run["_directory"], response_file).read_bytes()) if response_file else None
    source = {"originalId": original_ids[0] if len(original_ids) == 1 else None,
            "originalIdCandidates": original_ids if len(original_ids) > 1 else [],
            "originalIdStatus": "verified body correspondence" if len(original_ids) == 1 else "not uniquely available",
            "url": record.get("url"), "method": record.get("method", "GET"),
            "account": record.get("account", ""), "status": record.get("status"),
            "area": area, "locator": pointer or name, "line": text.count("\n", 0, offset) + 1,
            "column": offset - text.rfind("\n", 0, offset), "offset": offset, "length": length,
            "offsetUnit": "decoded text Unicode code points", "decodedTextSHA256": body_hash,
            "responseSHA256": response_hash, "file": record.get("decodedBodyFile") if area == "response.body" else response_file,
            "evidenceVerified": True}
    cache[cache_key] = (dict(source), [i for i, c in enumerate(text) if c == "\n"])
    return source


def supplemental_values(run):
    """Conservative literals from actual response JSON, headers and text.

    These are report-only observations, never attributed to product detection.
    No expression evaluation, URL joining, secret verification or target fetch.
    """
    output = []
    for record in run.get("transport", []):
        filename = record.get("decodedBodyFile")
        text = read_text_exact(bounded_path(run["_directory"], filename)) if filename else ""
        leaves = []
        try:
            leaves = list(json_leaves(text)) if text.lstrip().startswith(("{", "[")) else []
        except (ValueError, TypeError, RecursionError):
            pass
        for leaf in leaves:
            if leaf["parsed"] is None or isinstance(leaf["parsed"], (dict, list)):
                continue
            value = leaf["parsed"] if isinstance(leaf["parsed"], str) else leaf["rawLexeme"]
            # Source map source text is code, not a literal secret. Original
            # source strings remain in evidence; product extraction is separate.
            if "/sourcesContent/" in leaf["pointer"]:
                continue
            name = leaf["name"]
            context = "/".join(leaf["pointer"].split("/")[-3:])
            category = category_for(name, value, leaf["pointer"]) or category_for(context, value, leaf["pointer"])
            if category and value != "":
                output.append({"category": category, "name": name, "valueExact": value,
                               "rawLexeme": leaf["rawLexeme"], "sourceOrigin": "reportSupplemental",
                               "parser": "actual-response-json-scalar", "classification": "원본 응답 값 · 유효성 미검증",
                               "source": source_record(run, record, "response.body", text, leaf["offset"], leaf["length"], pointer=leaf["pointer"])})
        # Quoted literal assignments only: variable expressions, interpolation,
        # calls and minified concatenations do not qualify as literal values.
        literal = re.compile(r'''(?<![\w$-])(?P<name>[A-Za-z_$][\w$-]{1,80})["']?\s*[:=]\s*(?P<quote>["'])(?P<value>[^\r\n\\"']{1,4096})(?P=quote)''')
        if not leaves:
            for match in literal.finditer(text):
                value, name = match["value"], match["name"]
                category = category_for(name, value)
                if not category or (category in ("token", "key") and re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", value)):
                    continue
                end = match.end()
                if re.match(r"\s*\+", text[end:end + 16]):
                    continue
                output.append({"category": category, "name": name, "valueExact": value, "rawLexeme": value,
                               "sourceOrigin": "reportSupplemental", "parser": "actual-response-quoted-literal",
                               "classification": "원본 코드 리터럴 · 공개/예제/비밀 여부 미확정",
                               "source": source_record(run, record, "response.body", text, match.start("value"), len(value), name=name)})
        for match in re.finditer(r'''["'](?P<value>/(?:api|rest|graphql|v[1-9][0-9]*)(?:/[^\s<>"'`\\]*)?)["']''', text):
            value = match["value"]
            output.append({"category": "api", "name": "API path literal", "valueExact": value,
                           "rawLexeme": value, "sourceOrigin": "reportSupplemental", "parser": "actual-response-api-literal",
                           "classification": "문자열로 관측한 경로 · 자동 접근/존재 확정 아님",
                           "source": source_record(run, record, "response.body", text, match.start("value"), len(value), name="API path literal")})
        for match in re.finditer(r'''(?=`(?P<value>[^`\r\n\\]{1,4096})`)''', text):
            value = match["value"]
            if not value.startswith(("/", "${", "http://", "https://")) or value.count("{") != value.count("}") or not re.search(r"/(?:api|rest|graphql|v[1-9][0-9]*)(?:/|$)", value):
                continue
            dynamic = "${" in value
            output.append({"category": "api", "name": "API template literal" if dynamic else "API static backtick literal",
                           "valueExact": value, "rawLexeme": value, "sourceOrigin": "reportSupplemental",
                           "valueType": "RAW_TEMPLATE_INTERPOLATED" if dynamic else "STRING_LITERAL",
                           "parsingState": "관측 템플릿 원문 · 동적값 미확정" if dynamic else "관측한 정적 문자열",
                           "parser": "actual-response-backtick-api-literal",
                           "classification": "API 템플릿 관측 · 식 평가/동적값 복원/자동 요청 없음" if dynamic else "원본 API 경로 문자열 · 도달 여부 별도",
                           "attributes": {"observedLiteral": value, "resolution": "DYNAMIC_UNRESOLVED" if dynamic else "OBSERVED_LITERAL", "httpMethod": "?"},
                           "source": source_record(run, record, "response.body", text, match.start("value"), len(value), name="API backtick literal")})
        header_text = "\r\n".join(str(h.get("name", "")) + ": " + str(h.get("value", "")) for h in record.get("responseHeaders", []))
        cursor = 0
        for header in record.get("responseHeaders", []):
            name, value = str(header.get("name", "")), str(header.get("value", ""))
            category = category_for(name, value)
            if name.lower() in ("server", "x-powered-by", "x-aspnet-version", "x-runtime", "x-generator"):
                category = "environment"
            if name.lower() == "set-cookie":
                category = "token"
            if category and value:
                offset = cursor + len(name) + 2
                item = {"category": category, "name": name, "valueExact": value, "rawLexeme": value,
                        "sourceOrigin": "reportSupplemental", "parser": "actual-response-header",
                        "classification": "원본 응답 헤더 · 인증 유효성 미검증",
                        "source": source_record(run, record, "response.headers", header_text, offset, len(value), name=name)}
                item["source"]["offsetUnit"] = "reconstructed header block Unicode code points"
                item["source"]["headerName"] = name
                output.append(item)
            cursor += len(name) + 2 + len(value) + 2
    return output


def product_values(run):
    """Import exact product-export values and verify their recorded sources."""
    product = run.get("productReport", {})
    candidates = []
    if product.get("available"):
        directory = product.get("directory", "product-export")
        root = (run["_directory"] / directory).resolve()
        if not root.is_relative_to(run["_directory"].resolve()):
            raise ValueError("Product export escapes recorded run")
        if root.is_file() and zipfile.is_zipfile(root):
            with zipfile.ZipFile(root) as archive:
                candidates.append(json.loads(archive.read("report.json")))
        elif root.is_dir():
            if (root / "report.json").is_file():
                candidates.append(read_json(root / "report.json"))
            else:
                for path in sorted(root.glob("*.zip")):
                    with zipfile.ZipFile(path) as archive:
                        if "report.json" in archive.namelist():
                            candidates.append(json.loads(archive.read("report.json")))
        if not candidates:
            raise ValueError("Run declares an available product export but no readable report.json exists")
    output = []
    page_index = collections.defaultdict(list)
    for page in run.get("pages", []):
        page_index[(page.get("originalId"), page.get("account"), page.get("profileRevision"))].append(page)
    source_cache = {}
    for document in candidates:
        run["_product_export_summary"] = {"available": True, "complete": document.get("complete"), "state": document.get("state"),
                                          "pages": len(document.get("pages", [])), "values": len(document.get("values", [])),
                                          "partialPages": sum(1 for page in document.get("pages", []) if not page.get("complete")),
                                          "notes": document.get("notes", []),
                                          "pageNotes": [{"originalId": page.get("originalId"), "complete": page.get("complete"), "note": page.get("note")}
                                                        for page in document.get("pages", []) if not page.get("complete")]}
        for item in document.get("values", document.get("items", [])):
            if not isinstance(item, dict):
                raise ValueError("Unknown product report value schema")
            source = item.get("source", {})
            category = str(item.get("category", "")).lower()
            if category not in CATEGORIES or not isinstance(item.get("value"), str):
                raise ValueError("Unrecognized product category or exact value")
            pages = page_index.get((source.get("originalId"), source.get("account"), source.get("profileRevision")), [])
            page = pages[0] if pages and len({(p.get("responseFile"), p.get("decodedBodyFile")) for p in pages}) == 1 else None
            evidence_text = ""
            filename = None
            response_hash = None
            key = (source.get("originalId"), source.get("account"), source.get("profileRevision"), source.get("area"))
            if key in source_cache:
                evidence_text, encoded_source, filename, response_hash, decoded_hash = source_cache[key]
            elif page:
                response_file = bounded_path(run["_directory"], page["responseFile"])
                raw = response_file.read_bytes()
                response_hash = sha(raw)
                if source.get("area") == "DECODED_RESPONSE_BODY":
                    filename = page["decodedBodyFile"]
                    evidence_text = read_text_exact(bounded_path(run["_directory"], filename))
                else:
                    filename = page["responseFile"]
                    head = raw.split(b"\r\n\r\n", 1)[0].decode("utf-8")
                    if "\r\n" not in head:
                        head = raw.split(b"\n\n", 1)[0].decode("utf-8")
                    evidence_text = "\n".join(head.replace("\r\n", "\n").split("\n")[1:])
                encoded_source = evidence_text.encode("utf-16-le")
                decoded_hash = sha(evidence_text.encode("utf-8"))
                source_cache[key] = (evidence_text, encoded_source, filename, response_hash, decoded_hash)
            offset, length = int(source.get("offset", -1)), int(source.get("length", -1))
            raw_text = item.get("rawText", "")
            if offset < 0 or length < 0:
                raise ValueError("Product value has no precise source span")
            span = encoded_source[offset * 2:(offset + length) * 2].decode("utf-16-le") if page else None
            verified = page is not None and span == raw_text and response_hash == source.get("responseSha256")
            if not verified:
                raise ValueError("Product value does not match its exact OriginalID, response hash and UTF-16 source span")
            normalized_source = {"originalId": source.get("originalId"), "originalIdStatus": "verified exact product OriginalID",
                                 "url": source.get("url"), "method": source.get("method"), "account": source.get("account"),
                                 "profileRevision": source.get("profileRevision"), "status": source.get("responseStatus"),
                                 "area": "response.body" if source.get("area") == "DECODED_RESPONSE_BODY" else "response.headers",
                                 "locator": source.get("locator"), "line": source.get("line"), "offset": offset, "length": length,
                                 "offsetUnit": "UTF-16 characters in decoded response body or normalized LF header block",
                                 "responseSHA256": source.get("responseSha256"), "decodedTextSHA256": decoded_hash,
                                 "file": filename, "evidenceVerified": verified}
            output.append({"category": category, "name": item.get("name", ""), "valueExact": item["value"], "rawLexeme": raw_text,
                           "valueType": item.get("valueType"), "classification": item.get("classification", "UNVERIFIED"),
                           "parsingState": ("원문 리터럴 보존 · 디코딩 미확정" if item.get("valueType") == "RAW_LITERAL_UNDECODED" else
                                            "템플릿 원문 보존 · 동적값 미확정 · 식 평가 없음" if item.get("valueType") == "RAW_TEMPLATE_INTERPOLATED" else "파싱 값"),
                           "sourceOrigin": "productDetected" if item.get("provenance") == "PRODUCT_FINDING" else "productReportSupplement",
                           "parser": item.get("attributes", {}).get("parser", "product-export"), "source": normalized_source,
                           "attributes": item.get("attributes", {}), "confidence": item.get("confidence")})
    return output


def load_runs(results: Path):
    runs = []
    for path in sorted(results.glob("**/run.json")):
        run = read_json(path)
        required = ("version", "siteId", "scenario", "jarSHA256", "runId", "state", "sent")
        if any(key not in run for key in required):
            raise ValueError(f"Recorded run schema incomplete: {path}")
        run["_directory"] = path.parent.resolve()
        run["_file"] = path.resolve()
        runs.append(run)
    if not runs:
        raise ValueError("No actual run.json files found; a report cannot be built from invented results")
    versions = collections.defaultdict(set)
    for run in runs:
        digest = run.get("jarSHA256", "")
        if not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Every actual run requires a complete immutable JAR SHA-256")
        versions[run["version"]].add(digest)
    if any(len(hashes) != 1 for hashes in versions.values()):
        raise ValueError("Mixed candidate JAR hashes for one version; retain setup attempts outside the final results tree")
    runs.sort(key=lambda run: (SITE_ORDER.get(run["siteId"], 99), run["siteId"], run["scenario"] != "root", run["scenario"], run["version"]))
    return runs


def enrich(values, run):
    output, seen = [], set()
    spans = collections.defaultdict(list)
    for original in values:
        item = dict(original)
        if not isinstance(item.get("valueExact"), str) or item.get("category") not in CATEGORIES:
            raise ValueError("Canonical value requires exact text and a known category")
        source = item.get("source", {})
        key = (item["category"], item["valueExact"], source.get("file"), source.get("offset"), source.get("locator"), item.get("sourceOrigin"))
        if key in seen:
            continue
        span_key = (item["category"], item["valueExact"], source.get("file"), item.get("sourceOrigin"), source.get("account"))
        start = source.get("offset")
        length = source.get("length")
        if isinstance(start, int) and isinstance(length, int):
            end = start + max(1, length)
            if any(start < previous_end and previous_start < end for previous_start, previous_end in spans[span_key]):
                continue
            spans[span_key].append((start, end))
        seen.add(key)
        item.update({"version": str(run["version"]), "siteId": run["siteId"], "siteName": SITE_NAMES.get(run["siteId"], run["siteId"]),
                     "scenario": run["scenario"], "runId": run["runId"], "jarSHA256": run["jarSHA256"],
                     "categoryLabelKR": CATEGORIES[item["category"]]})
        output.append(item)
    return output


def additional_values_only(product, supplemental, run):
    """Prefer product provenance for the same value at an overlapping source."""
    indexed = collections.defaultdict(list)
    for item in product:
        source = item["source"]
        indexed[(item["valueExact"], source.get("responseSHA256"), source.get("area"), source.get("account"), source.get("url"))].append(source)
    astral_cache = {}
    result = []
    for item in supplemental:
        source = item["source"]
        matches = indexed.get((item["valueExact"], source.get("responseSHA256"), source.get("area"), source.get("account"), source.get("url")), [])
        if not matches:
            result.append(item)
            continue
        if source.get("area") == "response.headers":
            duplicate = any(str(s.get("locator", "")).casefold() == str(source.get("locator", "")).casefold() for s in matches)
        else:
            filename = source.get("file")
            if filename not in astral_cache:
                text = read_text_exact(bounded_path(run["_directory"], filename))
                astral_cache[filename] = [i for i, char in enumerate(text) if ord(char) > 0xffff]
            astral = astral_cache[filename]
            start = source["offset"] + bisect.bisect_left(astral, source["offset"])
            end_position = source["offset"] + source["length"]
            end = end_position + bisect.bisect_left(astral, end_position)
            duplicate = any(start < s["offset"] + max(1, s["length"]) and s["offset"] < max(start + 1, end) for s in matches)
        if not duplicate:
            result.append(item)
    return result


def test_login_values(metadata_root, site_ids, out):
    """Explicit local test auth artifacts only; never environment credentials."""
    output = []
    for site_id in site_ids:
        candidates = [metadata_root / site_id / "private-auth.json", metadata_root / f"{site_id}-private-auth.json"]
        auth_file = next((p for p in candidates if p.is_file()), None)
        if auth_file is None:
            continue
        document = read_json(auth_file)
        exact = read_text_exact(auth_file)
        leaves = list(json_leaves(exact))
        pairs = []
        for leaf in leaves:
            pointer, name, value = leaf["pointer"], leaf["name"], leaf["parsed"]
            if not isinstance(value, str) or not value:
                continue
            if re.fullmatch(r"token|access_token|refresh_token|csrf|csrfToken|cookie|password|secret", name, re.I) or re.fullmatch(r"/cookies/[0-9]+/value", pointer):
                category = "sensitive" if name.lower() in ("password", "secret") else "token"
                pairs.append(("test login " + name, value, pointer, category))
        if not pairs:
            continue
        evidence_file = Path("test-login") / site_id / "private-auth.json"
        (out / evidence_file).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(auth_file, out / evidence_file)
        spans = {leaf["pointer"]: leaf for leaf in leaves}
        for name, value, pointer, category in pairs:
            span = spans.get(pointer)
            if span is None or span["parsed"] != value:
                raise ValueError("Explicit test-login value does not match its private auth artifact")
            output.append({"version": "test-setup", "siteId": site_id, "siteName": SITE_NAMES.get(site_id, site_id),
                           "scenario": "test-login", "runId": "test-setup", "jarSHA256": "", "category": category, "categoryLabelKR": CATEGORIES[category],
                           "name": name, "valueExact": value, "rawLexeme": span["rawLexeme"], "sourceOrigin": "testLoginAuth",
                           "parser": "explicit-test-login-auth-artifact", "classification": "테스트에 사용한 로컬 seed 계정 인증값 · 탐지 성과 제외",
                           "source": {"originalId": None, "originalIdStatus": "test setup artifact, not a product response OriginalID",
                                      "url": document.get("loginURL", document.get("origin", "")), "method": "POST", "account": document.get("account", "test setup"),
                                      "status": None, "area": "test-login artifact", "locator": pointer,
                                      "line": exact.count("\n", 0, span["offset"]) + 1, "offset": span["offset"], "length": span["length"],
                                      "offsetUnit": "private auth JSON Unicode code points", "responseSHA256": None,
                                      "decodedTextSHA256": sha(auth_file.read_bytes()), "file": str(evidence_file), "evidenceVerified": True}})
    return output


def csv_values(path, values):
    fields = ["id", "version", "siteId", "scenario", "collectionMode", "sourceOrigin", "category", "name", "valueExact", "rawLexeme", "valueType", "parsingState", "classification", "valueSHA256", "rawLexemeSHA256",
              "originalId", "originalIdStatus", "url", "method", "account", "status", "area", "locator", "line", "column", "offset", "length",
              "offsetUnit", "responseSHA256", "decodedTextSHA256", "file", "evidenceVerified", "parser"]
    with path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        for item in values:
            row = {key: item.get(key, "") for key in fields}
            row.update({key: item.get("source", {}).get(key, "") for key in fields if key in item.get("source", {})})
            writer.writerow(row)


def public_summary(bundle):
    """A strict allowlist: no URLs, raw values, tokens, bodies or log messages."""
    return {"schemaVersion": 1, "generatedAt": bundle["generatedAt"], "versions": bundle["versions"],
            "siteCount": len(bundle["sites"]), "runCount": len(bundle["runs"]),
            "sites": [{"id": s["id"], "name": s["name"]} for s in bundle["sites"]],
            "runs": [{key: run.get(key) for key in ("version", "siteId", "scenario", "jarSHA256", "state", "sent", "completed", "pending", "excluded", "failed", "partial", "transportResponses", "transportErrors", "findingCount", "findingCategoryCounts")} for run in bundle["runs"]],
            "supplementalRunCount": len(bundle.get("supplementalRuns", [])),
            "supplementalRuns": [{key: run.get(key) for key in ("version", "siteId", "scenario", "jarSHA256", "state", "sent", "transportResponses", "transportErrors", "collectionMode")} for run in bundle.get("supplementalRuns", [])],
            "valueCounts": bundle["valueCounts"], "limitations": bundle["limitations"],
            "rawValueReportsPublishedToGit": False,
            "note": "현재 실행 수치만 포함. 항목 수는 원본 위치별 값의 수이며 취약점 수나 탐지 정확도가 아님."}


def html_report(path, bundle):
    payload = json.dumps(bundle, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    content = r'''<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Workbench Simple 재시험 · 원문 값 보고서</title>
<style>
:root{font-family:system-ui,'Malgun Gothic',sans-serif;color:#172e42;background:#edf2f7}*{box-sizing:border-box}body{margin:0}header{background:#102b45;color:white;padding:36px max(22px,calc((100vw - 1500px)/2))}h1{font-size:30px;margin:10px 0}h2{font-size:22px;margin:0 0 18px}h3{margin:22px 0 10px}p{line-height:1.7}main{max-width:1544px;margin:auto;padding:22px}.card{background:white;border-radius:14px;padding:24px;margin:0 0 20px;box-shadow:0 2px 12px #102b4509}.muted{color:#52677c;font-size:13px}.badge{display:inline-block;border-radius:5px;background:#e5f1ef;padding:3px 8px;color:#126961;font-size:12px}.stats{display:flex;gap:14px;flex-wrap:wrap}.stats>div{min-width:140px;border-left:3px solid #25a49b;padding-left:12px}.stats b{font-size:28px;display:block}.table-wrap{overflow:auto;border:1px solid #dfe7ef;border-radius:8px}table{border-collapse:collapse;width:100%;font-size:13px}th,td{text-align:left;vertical-align:top;border-bottom:1px solid #dfe7ef;padding:10px}th{position:sticky;top:0;background:#e9f0f6;white-space:nowrap}tr:last-child td{border-bottom:0}pre{font:12px/1.55 ui-monospace,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;margin:0;tab-size:2}.value{min-width:260px;max-width:650px;color:#0b504d}.evidence{min-width:260px;max-width:460px}.filters{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:16px}input,select,button{border:1px solid #b8c7d5;border-radius:7px;padding:10px;font:inherit;max-width:100%}input{flex:1;min-width:180px}button{background:#fff;color:#174c6e;cursor:pointer}.page-buttons{display:flex;gap:12px;align-items:center;margin:14px 0}.note{border-left:3px solid #e2b36d;padding:10px 14px;background:#fff9ef}details{margin-top:9px}summary{cursor:pointer}.small{font-size:12px}.pill{display:inline-block;padding:2px 6px;border-radius:4px;background:#eff2f5;margin-right:4px}#values tbody tr:hover{background:#f8fbfd}a{color:#137484}header a{color:#9ee6df}@media print{.filters,.page-buttons,button{display:none}main{max-width:none}.card{break-inside:avoid}header{padding:18px}pre{font-size:9px}th{position:static}}
</style><header><div class="small">WORKBENCH SIMPLE · LOCAL RETEST EVIDENCE</div><h1>개선 후 재시험 · 주요 파싱 값 분류</h1><p id="intro"></p><div class="stats" id="stats"></div></header><main>
<section class="card"><h2>수정 전후 실행 결과</h2><p class="muted">동일 사이트의 실행은 시나리오·계정·요청 예산에 따라 분리합니다. 큐 종료와 전체 사이트 점검 완료는 다릅니다. 실패·부분·미확인 수를 함께 읽으세요.</p><div class="table-wrap"><table id="runs"><thead><tr><th>사이트</th><th>버전·시나리오</th><th>상태</th><th>전송</th><th>응답/전송오류</th><th>완료</th><th>대기</th><th>제외</th><th>실패</th><th>부분</th><th>제품 항목</th><th>Katana</th></tr></thead><tbody></tbody></table></div><div id="export-notes"></div></section>
<section class="card"><h2>원문 값 검색·분류</h2><p class="muted">제품 탐지, 제품 보고서 보충 파싱, 재시험 보고서의 추가 파싱, 시험에 사용한 로그인 인증값을 구분합니다. 값은 축약하지 않습니다. 디코딩 미확정 원문 리터럴은 별도로 표시합니다. 경로 문자열 발견은 요청 성공을 의미하지 않으며 키·토큰은 유효성을 검사하지 않았습니다. 정확한 문자열은 ‘원문 복사’ 또는 JSON/CSV에서 확인하세요.</p><div class="filters"><input id="search" aria-label="원문과 근거 검색" placeholder="값·키 이름·URL·원본 ID·근거 검색"><select id="version" aria-label="버전"></select><select id="site" aria-label="사이트"></select><select id="category" aria-label="분류"></select><select id="origin" aria-label="출처"></select></div><div class="page-buttons"><button id="prev">이전</button><span id="count"></span><button id="next">다음</button><button id="download">선택 결과 JSON 저장</button></div><div class="table-wrap"><table id="values"><thead><tr><th>항목·분류</th><th>파싱한 값 원문</th><th>원본·위치·응답 근거</th></tr></thead><tbody></tbody></table></div></section>
<section class="card"><h2>사이트 출처와 배포 조건</h2><div id="sites"></div></section><section class="card"><h2>조치 확인·남은 제한</h2><div id="checks"></div><ul id="limits"></ul></section><p class="muted">같은 폴더의 raw-values.json, raw-values.csv, report.json과 evidence.zip에서 전체 값과 원본 증거를 확인할 수 있습니다. 이 파일은 외부 서버로 값을 전송하지 않습니다.</p></main><script id="data" type="application/json">__PAYLOAD__</script><script>
'use strict';const d=JSON.parse(document.getElementById('data').textContent);const $=id=>document.getElementById(id);const text=(tag,value,cls)=>{const e=document.createElement(tag);e.textContent=value??'';if(cls)e.className=cls;return e};const cell=(tr,value)=>{const e=text('td',value);tr.append(e);return e};
$('intro').textContent=`${d.versions.join(' → ')} · ${d.sites.length}개 사이트 · ${d.generatedAt}`;for(const [n,v]of[['실제 실행',d.runs.length],['파싱 값·위치',d.values.length],['분류',Object.keys(d.categories).length]]){const e=document.createElement('div');e.append(text('b',v),text('span',n));$('stats').append(e)}
for(const r of d.runs){const tr=document.createElement('tr');for(const v of[r.siteName,r.version+' / '+r.scenario,r.state+(r.wallLimitReached?' · 시험시간한도':''),r.sent,`${r.transportResponses}/${r.transportErrors}`,r.completed,r.pending,r.excluded,r.failed,r.partial,r.findingCount,r.katana?.state??'미기록'])cell(tr,v);$('runs').tBodies[0].append(tr);const ex=r.productExport||{};const detail=document.createElement('details');detail.append(text('summary',`${r.siteName} ${r.version}/${r.scenario} · 제품 원문 보고서 ${ex.available?'사용 가능':'미제공'}${ex.available?' · 부분 원본 '+ex.partialPages:''}`));detail.append(text('pre',JSON.stringify({productExport:ex,parameters:r.parameters,wallLimitReached:r.wallLimitReached,auditStopReason:r.auditStopReason,transportErrors:r.transportErrorDetails},null,2)));$('export-notes').append(detail)}
function options(id,items,title){$(id).append(new Option(title,''));for(const [value,label]of items)$(id).append(new Option(label,value))}options('version',d.versions.map(x=>[x,x]),'모든 버전');options('site',d.sites.map(x=>[x.id,x.name]),'모든 사이트');options('category',Object.entries(d.categories),'모든 분류');options('origin',Object.entries(d.origins),'모든 출처');
let page=0,selected=[];const perPage=50;function filter(){const q=$('search').value.toLocaleLowerCase();selected=d.values.filter(v=>(!$('version').value||v.version===$('version').value)&&(!$('site').value||v.siteId===$('site').value)&&(!$('category').value||v.category===$('category').value)&&(!$('origin').value||v.sourceOrigin===$('origin').value)&&(!q||JSON.stringify(v).toLocaleLowerCase().includes(q)));page=0;render()}
async function copy(value){try{await navigator.clipboard.writeText(value)}catch{const t=text('textarea',value);document.body.append(t);t.select();document.execCommand('copy');t.remove()}}
function render(){const body=$('values').tBodies[0];body.replaceChildren();for(const v of selected.slice(page*perPage,(page+1)*perPage)){const tr=document.createElement('tr'),a=cell(tr,'');a.append(text('strong',v.name||v.categoryLabelKR),text('p',v.siteName+' · '+v.version+' / '+v.scenario+(v.collectionMode==='supplemental-direct-contrast'?' · 별도 직접 대조':''),'small'),text('span',v.categoryLabelKR,'badge'),text('p',d.origins[v.sourceOrigin]||v.sourceOrigin,'small'),text('p',v.classification,'muted'),text('p',v.parsingState||v.valueType||'파싱 값','muted'));const b=cell(tr,'');b.append(text('pre',v.valueExact,'value'));const button=text('button','원문 복사');button.addEventListener('click',()=>copy(v.valueExact));b.append(button);if(v.rawLexeme!==undefined&&v.rawLexeme!==v.valueExact){const detail=document.createElement('details');detail.append(text('summary','응답의 원문 표기'),text('pre',v.rawLexeme));b.append(detail)}const c=cell(tr,'');c.className='evidence';const s=v.source||{};c.append(text('pre',[`${s.method||''} ${s.url||''}`,`HTTP ${s.status??'미기록'} / 계정 ${s.account||'미기록'}`,`OriginalID: ${s.originalId||'고유 원본 ID 연결 불가'}`,`${s.area||''} ${s.locator||''}`,`줄 ${s.line??'—'} / offset ${s.offset??'—'} / length ${s.length??'—'}`,s.offsetUnit||'',`응답 SHA256: ${s.responseSHA256||'미기록'}`,`분석텍스트 SHA256: ${s.decodedTextSHA256||'미기록'}`,`원본 파일: ${s.file||'미기록'}`,`증거 검증: ${s.evidenceVerified===true?'확인':s.evidenceVerified===false?'불일치':'미기록'}`,`parser: ${v.parser||''}`].join('\n')));if(v.attributes&&Object.keys(v.attributes).length){const detail=document.createElement('details');detail.append(text('summary','관측·복원 API 메타데이터'),text('pre',JSON.stringify(v.attributes,null,2)));c.append(detail)}body.append(tr)}$('count').textContent=`${selected.length.toLocaleString()} / ${d.values.length.toLocaleString()}건 · ${selected.length?page+1:0}/${Math.ceil(selected.length/perPage)}페이지`;$('prev').disabled=page===0;$('next').disabled=(page+1)*perPage>=selected.length}
for(const id of['version','site','category','origin'])$(id).addEventListener('change',filter);let delay;$('search').addEventListener('input',()=>{clearTimeout(delay);delay=setTimeout(filter,130)});$('prev').onclick=()=>{page--;render()};$('next').onclick=()=>{page++;render()};$('download').onclick=()=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(selected,null,2)],{type:'application/json'}));a.download='selected-raw-values.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000)};
for(const site of d.sites){const e=document.createElement('article');e.append(text('h3',site.name));const m=site.metadata||{};e.append(text('pre',[m.sourceURL||m.repository||'',m.sourceCommit||m.commit||'',m.deployment||'',...(m.limitations||[])].join('\n')));$('sites').append(e)}for(const check of d.checks||[]){const e=document.createElement('p');e.append(text('strong',`${check.status||'미기록'} · ${check.id||''} ${check.title||''}`),text('span',' '+(check.detail||'')));$('checks').append(e)}if(d.supplementalRuns?.length){const e=document.createElement('details');e.append(text('summary','별도 merchant realm 직접 대조 2회 · 본28회/자동 발견과 분리'),text('pre',JSON.stringify({runs:d.supplementalRuns,validation:d.directSupplementValidation},null,2)));$('checks').append(e)}if(d.navigationValidation){const e=document.createElement('details');e.append(text('summary','별도 실제 Chromium DOM fixture 검증 · 일곱 사이트 HTTP/Katana와 별도'),text('pre',JSON.stringify(d.navigationValidation,null,2)));$('checks').append(e)}for(const l of d.limitations)$('limits').append(text('li',l));filter();
</script></html>'''
    path.write_text(content.replace("__PAYLOAD__", payload), encoding="utf-8")


def representative_score(value):
    """Prefer meaningful response data over library labels in the PDF only."""
    source, text, category = value.get("source", {}), value["valueExact"], value["category"]
    origin = {"productDetected": 0, "productReportSupplement": 1, "reportSupplemental": 2}.get(value.get("sourceOrigin"), 3)
    locator, url, name = str(source.get("locator", "")), str(source.get("url", "")), str(value.get("name", ""))
    json_value = locator.startswith(("JSON:", "/")) or "json-scalar" in value.get("parser", "")
    actual_api_response = bool(re.search(r"/(?:api|rest)/", url))
    library = bool(re.search(r"swagger-ui|jquery|polyfills|vendor|runtime\.", url, re.I))
    meaningful = 0
    if category == "sensitive":
        meaningful = 0 if re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+|\d{3}-\d{2}-\d{4}|\+?[0-9][0-9 ()-]{8,}", text) else 1
    elif category in ("token", "key"):
        meaningful = 0 if len(text) >= 20 and not re.search(r"\s", text) else 1
    elif category == "environment":
        meaningful = 0 if source.get("area") == "response.headers" or (json_value and actual_api_response) else 1
    elif category == "api":
        meaningful = 0 if re.search(r"/(?:api|rest|graphql)(?:/|$)", text) else 1
        core = (0 if "/rest/user/login" in text else 1 if "/rest/basket/" in text and not any(part in text for part in ("/checkout", "/coupon"))
                else 2 if "/rest/products/search" in text else 3)
        return (core, meaningful, library, origin, not actual_api_response, len(text) > 300, name)
    return (origin, library, meaningful, not actual_api_response, not json_value, len(text) > 300, name)


def build_pdf(path, bundle, font):
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, KeepTogether, Flowable
    pdfmetrics.registerFont(TTFont("RetestNanum", str(font)))
    pdfmetrics.registerFontFamily("RetestNanum", normal="RetestNanum", bold="RetestNanum", italic="RetestNanum", boldItalic="RetestNanum")
    navy, teal, gray = [colors.HexColor(x) for x in ("#15364e", "#087e80", "#506577")]
    styles = {key: ParagraphStyle(key, fontName="RetestNanum", fontSize=size, leading=leading, wordWrap="CJK", textColor=color, spaceAfter=after)
              for key, size, leading, color, after in [("title", 23, 33, navy, 15), ("h", 14, 22, teal, 10), ("body", 10, 16, navy, 8),
                                                        ("small", 8.1, 12.5, gray, 6), ("cell", 8.2, 12.4, navy, 0), ("label", 8, 12, teal, 10)]}
    class ScreenshotMenuViewport(Flowable):
        """Document viewport over the original PNG; source evidence is unmodified."""
        def __init__(self, filename):
            from reportlab.lib.utils import ImageReader
            super().__init__()
            self.reader = ImageReader(str(filename))
            self.pixel_width, self.pixel_height = self.reader.getSize()
            self.scale = min(475 / min(640, self.pixel_width), 120 / min(160, self.pixel_height))
            self.width = min(640, self.pixel_width) * self.scale
            self.height = min(160, self.pixel_height) * self.scale
        def draw(self):
            self.canv.saveState()
            viewport = self.canv.beginPath()
            viewport.rect(0, 0, self.width, self.height)
            self.canv.clipPath(viewport, stroke=0, fill=0)
            self.canv.drawImage(self.reader, 0, self.height - self.pixel_height * self.scale,
                                width=self.pixel_width * self.scale, height=self.pixel_height * self.scale)
            self.canv.restoreState()
    story = []
    def p(value, style="body"):
        return Paragraph(escape(str(value)).replace("\n", "<br/>"), styles[style])
    def add(value, style="body"):
        story.append(p(value, style))
    def section(title, subtitle=""):
        if story:
            story.append(PageBreak())
        add("WORKBENCH SIMPLE / 실제 HTTP 원본 기반 재시험", "label")
        add(title, "title")
        if subtitle:
            add(subtitle, "small")
    def table(headers, rows, widths):
        t = Table([[p(v, "cell") for v in headers]] + [[p(v, "cell") for v in row] for row in rows], colWidths=widths, repeatRows=1, hAlign="LEFT")
        t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#e6eff4")), ("VALIGN", (0, 0), (-1, -1), "TOP"),
                               ("LEFTPADDING", (0, 0), (-1, -1), 7), ("RIGHTPADDING", (0, 0), (-1, -1), 7),
                               ("TOPPADDING", (0, 0), (-1, -1), 7), ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
                               ("LINEBELOW", (0, 0), (-1, -1), .4, colors.HexColor("#d9e3ec"))]))
        story.extend((t, Spacer(1, 10)))
    section("개선 후 재시험 보고서", " · ".join(bundle["versions"]) + " / " + bundle["generatedAt"])
    add(f"{len(bundle['sites'])}개 사이트의 실제 실행 {len(bundle['runs'])}개와 파싱 값·위치 {len(bundle['values']):,}개를 정리했습니다.", "h")
    if bundle.get("supplementalRuns"):
        add(f"본28회 외에 별도 제어 직접 대조 {len(bundle['supplementalRuns'])}회의 원문도 표시합니다. 직접 지정한 결과는 자동 발견 성과에 넣지 않습니다.", "small")
    add("이 보고서는 현재 빌드의 실제 Runner·Katana 실행과 로컬 원본 앱 응답을 사용합니다. 과거 공유 대화의 검사 건수는 이번 결과로 합산하지 않았습니다.")
    add("API·민감정보·Token·Key·환경·DB·내부 정보·기타 주요정보를 원문 값과 함께 분류합니다. 제품 탐지, 제품 보고서 보충 파싱, 재시험 보고서의 추가 파싱, 시험 로그인 인증값은 별도 출처로 표시합니다.")
    if bundle.get("regressionValidation"):
        profile = bundle["regressionValidation"]["defaultProfile"]
        add(f"별도 제품 전체 자동 검사 {profile['totalChecks']:,}개를 같은 JAR로 통과했습니다. Workbench {profile['regressionChecks']:,}개·페이로드 HTTP{profile['payloadHttpChecks']}개·Mapper Java{profile['mapperJavaChecks']}개·Node{profile['browserHelperNodeChecks']}개의 합계이며, 사이트 전송 수·값 행 수·보고서 형식 검사와 별도로 집계합니다.", "small")
    table(["분류", "원본 위치별 값 수"], [[label, bundle["valueCounts"]["category"].get(key, 0)] for key, label in CATEGORIES.items()], [340, 155])
    add("PDF는 읽기 좋은 대표 항목을 선별합니다. 모든 값의 축약 없는 원문·JSON 원문 표기·정확한 근거는 같은 폴더의 HTML·JSON·CSV에 있습니다. 항목 수는 취약점 수나 탐지 정확도가 아닙니다.", "small")
    section("실행 범위와 판정 방법")
    for limit in bundle["limitations"]:
        add("• " + limit)
    table(["값 출처", "항목 수"], [[label, bundle["valueCounts"]["origin"].get(key, 0)] for key, label in ORIGINS.items()], [340, 155])
    add("각 값에는 원본 ID(고유 연결이 가능할 때), HTTP 상태·계정·메서드·URL·헤더/본문 위치·offset·응답/분석텍스트 SHA-256을 제공합니다. offset은 명시한 단위이며 압축 전 wire 바이트 위치와 다를 수 있습니다.")
    section("사이트별 수정 전후 결과")
    rows = []
    for run in bundle["runs"]:
        rows.append([run["siteName"], run["version"] + "\n" + run["scenario"], run["state"] + ("\n시험 시간 한도" if run.get("wallLimitReached") else ""),
                     f"{run.get('sent', '—')} / {run.get('transportResponses', '—')}",
                     run.get("findingCount", "—"),
                     f"{run.get('failed', '—')} / {run.get('partial', '—')} / {run.get('pending', '—')}"])
    table(["사이트", "버전·시나리오", "상태", "전송/응답", "제품 항목", "실패/부분/대기"], rows, [95, 100, 65, 75, 60, 100])
    add("전송 수에는 기준·계정·리디렉션 요청이 포함될 수 있습니다. 실제 응답 수에는 HTTP401·404 등 오류 상태 응답도 포함하고 I/O 전송 실패와 구분합니다. 사이트·버전·시나리오별 조건은 HTML에서 확인하세요.", "small")
    if bundle.get("checks"):
        section("개선 사항 조치 확인")
        table(["항목", "현재 결과", "실제 근거·제한"], [[str(c.get("id", "")) + " " + str(c.get("title", "")), c.get("status", "미기록"), c.get("detail", "")] for c in bundle["checks"]], [150, 72, 273])
    selected_ids = []
    for site in bundle["sites"]:
        section(site["name"], "실제 앱 출처·배포 조건과 대표 파싱 값")
        meta = site.get("metadata", {})
        add(meta.get("sourceURL", meta.get("repository", "출처는 실행 증거 참조")), "small")
        pin = next((meta[key] for key in ("sourceCommit", "commit", "archiveSHA256", "archiveSha256", "warSHA256", "builtArtifactSha256") if meta.get(key)), "미기록")
        add("고정 원본 지문: " + str(pin), "small")
        if meta.get("deployment"):
            add(meta["deployment"], "small")
        for limit in meta.get("limitations", []):
            add("• " + limit, "small")
        site_values = [v for v in bundle["values"] if v["siteId"] == site["id"] and v["version"] == bundle["versions"][-1]]
        add(f"최신 버전의 원본 위치별 값 {len(site_values):,}개. 아래 항목은 대표 원문이며 전체는 HTML/JSON/CSV에 수록했습니다.", "h")
        for category, label in CATEGORIES.items():
            candidates = [v for v in site_values if v["category"] == category and v.get("sourceOrigin") != "testLoginAuth" and v.get("valueType") != "RAW_LITERAL_UNDECODED" and len(v["valueExact"]) <= 1400
                          and v["valueExact"] not in ("null", "undefined", "true", "false", "")
                          and (category not in ("token", "key") or (len(v["valueExact"]) >= 8 and v.get("valueType") != "RAW_TEMPLATE_INTERPOLATED"))
                          and (category != "api" or not re.search(r"\.(?:css|js|map|png|svg|woff2?)(?:\?|$)", v["valueExact"], re.I))]
            candidates.sort(key=representative_score)
            unique, picked = set(), []
            for value in candidates:
                if value["valueExact"] not in unique:
                    unique.add(value["valueExact"])
                    picked.append(value)
                if len(picked) == (3 if category == "api" and site["id"] == "juice-shop" else 2):
                    break
            if not picked:
                continue
            add(label, "h")
            for item in picked:
                selected_ids.append(item["id"])
                source = item.get("source", {})
                classification = str(item.get("classification", "UNVERIFIED"))
                add(f"{item.get('name', '')} / {ORIGINS.get(item['sourceOrigin'], item['sourceOrigin'])}\n시나리오: {item.get('scenario', '미기록')} / {item.get('collectionMode', '원본 수집')}\n분류: {CLASSIFICATIONS.get(classification, classification)}", "small")
                add(item["valueExact"])
                add(f"{source.get('method', '')} {source.get('url', '')}\nHTTP {source.get('status', '미기록')} / 계정 {source.get('account', '미기록')} / OriginalID {source.get('originalId') or '고유 연결 불가'}\n{source.get('area', '')} {source.get('locator', '')} / 줄 {source.get('line', '—')} / offset {source.get('offset', '—')} / {source.get('offsetUnit', '')}", "small")
        if not site_values:
            add("현재 실행 증거에서 원문 값 추출 결과가 없습니다. 이 상태를 민감정보 없음 또는 안전으로 해석하지 않습니다.")
    if bundle.get("supplementalRuns"):
        section("별도 merchant realm 직접 대조", "본28회와 분리한 실제 원본 VulnBank의 제어된 직접 seed 2회")
        add("자동 root 탐색의 1.3.2 실행에서는 merchant/me 방문이 관측되지 않았습니다. 그러므로 root 완료만으로 401 처리 개선이 실제 검증됐다고 주장하지 않습니다. 아래 두 실행은 해당 URL을 직접 지정한 별도 대조이며 자동 발견 성과에 포함하지 않습니다.")
        table(["버전·시나리오", "상태", "전송/응답", "실패/부분/대기"], [[r["version"] + " / " + r["scenario"], r["state"], str(r["sent"]) + " / " + str(r["transportResponses"]), str(r["failed"]) + " / " + str(r["partial"]) + " / " + str(r["pending"])] for r in bundle["supplementalRuns"]], [180, 95, 100, 120])
        proof = bundle.get("directSupplementValidation") or {}
        for name, result in proof.get("checks", {}).items():
            add(("PASS · " if result is True else "미확인 · ") + name, "small")
        add("이 두 실행의 원문 값은 collectionMode=supplemental-direct-contrast 및 scenario=realm-direct로 표시합니다. 전체 원본 ID·응답 SHA·위치는 다른 값과 같은 기준으로 검증합니다.", "small")
    if bundle.get("navigationValidation"):
        evidence = bundle["navigationValidation"]
        section("브라우저 메뉴 탐색 별도 검증", "실제 Chromium fixture · 일곱 원본 앱 HTTP/Katana 실행과 별도")
        add("브라우저 helper의 지연 메뉴·탐색 한도·이동 전 링크 수집을 실제 Chromium DOM과 운영 탐색 서비스에서 확인했습니다. Playwright의 탭·저장소 어댑터를 사용했으며 실제 MV3 서비스 worker·DNR 전송·Burp GUI 검증은 아닙니다.")
        before, after = evidence.get("before", {}), evidence.get("after", {})
        add(f"Chromium {evidence.get('chromium', '미기록')} · 수정 전 {before.get('checks', '미기록')}개 / 수정 후 {after.get('checks', '미기록')}개 조건 검사. 전후 검사는 겹치며 기본 회귀 합계에 더하지 않습니다.", "small")
        previous = {case.get("name"): case for case in before.get("cases", [])}
        labels = {"late": "1.8초 지연 메뉴", "replace": "화면 변경 전 링크", "capped": "12회 동작 한도", "navigate": "전체 페이지 이동", "busy": "계속 로딩 중인 화면", "overflow": "200개 DOM 후보 한도"}
        rows = []
        for case in after.get("cases", []):
            old = previous.get(case.get("name"), {})
            rows.append([labels.get(case.get("name"), case.get("name", "")), str(old.get("state", "미기록")) + " / " + str(old.get("menuCount", "—")),
                         str(case.get("state", "미기록")) + " / " + str(case.get("menuCount", "—")), case.get("message", "")])
        if rows:
            table(["검증 시나리오", "수정 전 상태/메뉴", "수정 후 상태/메뉴", "수정 후 미확인 근거"], rows, [120, 105, 105, 165])
        add("부분(partial)은 탐색 실패 건수 증가라는 뜻이 아닙니다. 한도·빈 leaf 문서·미확인 범위가 남았음을 보수적으로 알립니다. late와 replace는 발견한 메뉴를 유지하면서 도착 leaf의 미확인 상태도 함께 남깁니다.", "small")
        native = evidence.get("nativeExtension", {})
        if native.get("result") == "blocked":
            add("관리된 Chromium의 확장 정책이 unpacked helper 설치를 차단했습니다. 정책을 변경하지 않았으며 실제 확장 설치·MV3·DNR 동작은 미검증으로 유지합니다.", "small")
        for limit in evidence.get("limitations", []):
            add("• " + limit, "small")
    if bundle.get("navigationCaptures"):
        section("브라우저 메뉴 fixture 캡처", "합성 loopback 화면 · 운영 helper 서비스/실제 Chromium DOM · API 어댑터")
        add("두 화면은 지연 메뉴와 화면 변경 전 링크 보존을 확인한 별도 fixture 캡처입니다. 실제 7개 원본 앱의 브라우저 화면이나 native Burp 캡처가 아닙니다.", "small")
        for capture in bundle["navigationCaptures"]:
            add(capture["caption"], "h")
            story.extend((ScreenshotMenuViewport(path.parent / capture["file"]), Spacer(1, 10)))
            add("PDF는 원본 화면 상단의 메뉴 영역(최대640×160px)을 확대해 표시합니다. 수정하지 않은 전체 PNG는 ZIP에 보존했습니다.", "small")
            add("원본 PNG와 SHA-256: " + capture["file"] + " / " + capture["sha256"], "small")
    section("전체 원문과 재현 증거")
    add("report.html: 사이트·버전·분류·출처·원문·원본 ID 검색. 표의 값은 축약하지 않으며 원문 복사를 지원합니다.")
    add("raw-values.json / raw-values.csv: 모든 파싱 값, 원문 표기와 위치·해시. CSV는 원래 문자열을 보존하므로 스프레드시트에서 데이터로 가져오기를 사용합니다.")
    add("report.json: 실제 실행 집계와 원문 값 전체. evidence.zip: 보고서와 실제 실행 증거·원본 HTTP 및 SHA256SUMS.json.")
    add("테스트 로그인 인증값은 별도 출처로 분리되어 탐지 성능에 넣지 않습니다. 원본에서 추출한 값은 해당 로컬 실행의 관측 결과이며 외부 서비스에서 재사용하거나 키 유효성을 확인하지 않았습니다.")
    add(f"PDF에 대표 값 {len(selected_ids)}개를 실었습니다. 전체 값 {len(bundle['values']):,}개는 HTML/JSON/CSV에 있습니다.", "h")
    def footer(canvas, doc):
        canvas.saveState()
        canvas.setFont("RetestNanum", 8)
        canvas.setFillColor(gray)
        canvas.drawString(48, 28, "Workbench Simple · 로컬 원본 응답 재시험")
        canvas.drawRightString(A4[0] - 48, 28, str(doc.page))
        canvas.restoreState()
    doc = SimpleDocTemplate(str(path), pagesize=A4, rightMargin=50, leftMargin=50, topMargin=45, bottomMargin=48,
                            title="Workbench Simple 개선 후 재시험 · 원문 값 보고서", author="Workbench Simple")
    bookmarks = []
    def bookmark(flowable):
        if isinstance(flowable, Paragraph) and flowable.style.name == "title":
            key = "section-" + str(len(bookmarks) + 1)
            doc.canv.bookmarkPage(key)
            doc.canv.addOutlineEntry(flowable.getPlainText(), key, level=0, closed=False)
            bookmarks.append(key)
    doc.afterFlowable = bookmark
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    return selected_ids


def validate_artifacts(out, values, pdf_representatives):
    import fitz
    from PIL import Image, ImageDraw
    csv.field_size_limit(64 * 1024 * 1024)
    csv_rows = list(csv.DictReader((out / "raw-values.csv").open(encoding="utf-8-sig", newline="")))
    if [r["valueExact"] for r in csv_rows] != [v["valueExact"] for v in values]:
        raise ValueError("CSV roundtrip changed a parsed value")
    if read_json(out / "raw-values.json") != values:
        raise ValueError("JSON roundtrip changed parsed values")
    report = read_json(out / "report.json")
    script = re.search(r'<script id="data" type="application/json">(.*?)</script>', (out / "report.html").read_text(), re.S)
    if not script or json.loads(script[1]) != report:
        raise ValueError("HTML embedded data differs from full JSON report")
    pdf = fitz.open(out / PDF_NAME)
    errors, fonts, tiles = [], set(), []
    for index, page in enumerate(pdf):
        for font in page.get_fonts(full=True):
            fonts.add(font[3])
        for block in page.get_text("dict")["blocks"]:
            for line in block.get("lines", []):
                for span in line["spans"]:
                    x0, y0, x1, y1 = span["bbox"]
                    if x0 < 10 or y0 < 8 or x1 > page.rect.width - 10 or y1 > page.rect.height - 8:
                        errors.append({"page": index + 1, "text": span["text"][:50], "bbox": list(span["bbox"])})
        pix = page.get_pixmap(matrix=fitz.Matrix(.45, .45), alpha=False)
        image = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        tile = Image.new("RGB", (image.width + 8, image.height + 25), "#e2e8ef")
        tile.paste(image, (4, 21)); ImageDraw.Draw(tile).text((8, 3), str(index + 1), fill="#203040"); tiles.append(tile)
    cols, rows = 4, (len(tiles) + 3) // 4
    sheet = Image.new("RGB", (tiles[0].width * cols, tiles[0].height * rows), "#c9d3df")
    for i, tile in enumerate(tiles):
        sheet.paste(tile, ((i % cols) * tile.width, (i // cols) * tile.height))
    sheet.save(out / "pdf-contact-sheet.png")
    result = {"passed": not errors, "pdfPages": len(pdf), "pdfBookmarks": len(pdf.get_toc()), "pdfFonts": sorted(fonts), "clippingErrors": errors,
              "csvRoundtripExact": True, "jsonRoundtripExact": True, "htmlEmbeddedDataExact": True,
              "totalExactValues": len(values), "pdfRepresentativeIds": pdf_representatives,
              "visualReviewComplete": False, "visualReviewNote": "Inspect pdf-contact-sheet.png and representative full pages before marking complete."}
    write_json(out / "report-validation.json", result)
    if errors:
        raise ValueError(f"PDF clipping gate failed: {len(errors)} text spans")
    return result


def evidence_zip(out, runs):
    file_hashes = {str(path.relative_to(out)): sha(path.read_bytes()) for path in sorted(out.rglob("*")) if path.is_file() and path.name not in ("evidence.zip", "parsed-values.zip", "SHA256SUMS.json")}
    entries = []
    for run in runs:
        directory = run["_directory"]
        files = {run["_file"]}
        files.update(path for path in (directory / "runtime.json", directory / "execution.log", directory / "private-config.json") if path.is_file())
        for record in run.get("transport", []) + run.get("pages", []):
            for key in ("requestFile", "responseFile", "bodyFile", "decodedBodyFile"):
                if record.get(key):
                    files.add(bounded_path(directory, record[key]))
        product = run.get("productReport", {})
        if product.get("available") and product.get("directory"):
            p = (directory / product["directory"]).resolve()
            if p.is_relative_to(directory.resolve()):
                if p.is_dir():
                    files.update(f for f in p.rglob("*") if f.is_file())
                elif p.is_file():
                    files.add(p)
        for path in sorted(files):
            relative = Path("evidence") / run["version"] / run["siteId"] / run["scenario"] / path.relative_to(directory)
            file_hashes[str(relative)] = sha(path.read_bytes())
            entries.append((path, str(relative)))
    write_json(out / "SHA256SUMS.json", file_hashes)
    with zipfile.ZipFile(out / "evidence.zip", "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(out.rglob("*")):
            if path.is_file() and path.name not in ("evidence.zip", "parsed-values.zip"):
                archive.write(path, str(path.relative_to(out)))
        for path, name in entries:
            archive.write(path, name)
    with zipfile.ZipFile(out / "evidence.zip") as archive:
        if archive.testzip() is not None:
            raise ValueError("Evidence ZIP CRC verification failed")
    return {"path": str(out / "evidence.zip"), "sha256": sha((out / "evidence.zip").read_bytes()), "entries": len(file_hashes) + 1}


def parsed_values_zip(out, bundle):
    """Convenient report-only download; original HTTP corpus stays separate."""
    readme = (
        f"# Workbench Simple {AFTER_VERSION} · 주요 파싱 값 보고서\n\n"
        f"실제 로컬 원본 앱 {len(bundle['sites'])}개, 전후 본 실행 {len(bundle['runs'])}개와 별도 제어 직접 대조 {len(bundle.get('supplementalRuns', []))}개, 원문 값·위치 {len(bundle['values']):,}행입니다.\n\n"
        "- PDF: 개선 결과·전후 실행·대표 원문 값.\n"
        "- report.html: 전체 원문 검색·사이트/버전/분류/출처 필터·원문 복사.\n"
        "- raw-values.json / raw-values.csv: 전체 값을 축약 없이 보존.\n"
        "- report.json: 실행 조건·미확인·추출 한계·원본 위치/해시 및 원문 전체.\n"
        "- validation: PDF/데이터 동등성·로컬 HTML 조작 검증.\n\n"
        "제품 탐지, 제품 보고서 보충 파싱, 독립 보고서 추가 파싱, 테스트 로그인 인증값을 구분합니다. "
        "템플릿의 ${...}는 관측한 원문이며 동적값을 평가하거나 실제 주소로 추측 호출하지 않았습니다. "
        "파싱 값·토큰·키는 이 로컬 실습 실행의 실제 응답/자료에서 얻은 값이며 유효성을 외부에서 검사하지 않았습니다.\n\n"
        "이 작은 묶음에는 전체 HTTP 요청·응답 corpus를 포함하지 않습니다. 원본 패킷과 상세 재현 증거는 별도 evidence.zip을 사용합니다. "
        "행 수는 고유 취약점 수나 탐지 정확도가 아닙니다. PAUSED/STOPPED·부분·미확인과 I/O 전송 실패는 보고서에 그대로 기록합니다.\n\n"
        "CSV의 원래 문자열을 유지하므로 스프레드시트의 텍스트 데이터 가져오기로 읽습니다. "
        "실행 중 생성한 로컬 seed 계정 인증값은 별도 testLoginAuth 출처이며 공개 Git 저장소에 올리지 않았습니다.\n"
    )
    (out / "README_KR.md").write_text(readme, encoding="utf-8")
    names = [PDF_NAME, "report.html", "raw-values.json", "raw-values.csv", "report.json",
             "report-validation.json", "browser-validation.json", "source-revalidation.json", "pdf-contact-sheet.png", "html-report-preview.png", "README_KR.md"]
    files = [out / name for name in names if (out / name).is_file()]
    files.extend(path for path in sorted((out / "sources").glob("*.json")) if path.is_file())
    files.extend(path for path in sorted((out / "navigation-captures").glob("*.png")) if path.is_file())
    manifest = {"schemaVersion": 1, "exactValueRows": len(bundle["values"]), "containsFullHttpCorpus": False,
                "files": {str(path.relative_to(out)): sha(path.read_bytes()) for path in files}}
    write_json(out / "parsed-values-manifest.json", manifest)
    files.append(out / "parsed-values-manifest.json")
    with zipfile.ZipFile(out / "parsed-values.zip", "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files:
            archive.write(path, str(path.relative_to(out)))
    with zipfile.ZipFile(out / "parsed-values.zip") as archive:
        if archive.testzip() is not None:
            raise ValueError("Parsed-values ZIP CRC verification failed")
    return {"path": str(out / "parsed-values.zip"), "sha256": sha((out / "parsed-values.zip").read_bytes()), "entries": len(files)}



def configure_versions(before, after):
    """Require an explicit increasing immutable release pair, never relabel runs."""
    global BEFORE_VERSION, AFTER_VERSION, PDF_NAME
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", before) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", after):
        raise ValueError("Release versions must be three-part numeric versions")
    if tuple(map(int, before.split("."))) >= tuple(map(int, after.split("."))):
        raise ValueError("Before release must precede after release")
    BEFORE_VERSION, AFTER_VERSION = before, after
    PDF_NAME = f"Workbench_Simple_{after}_Retest_KR.pdf"



def ordered_versions(runs):
    observed = {run["version"] for run in runs}
    if not observed.issubset({BEFORE_VERSION, AFTER_VERSION}):
        raise ValueError("Input corpus contains a release outside the selected pair")
    return sorted(observed, key=lambda value: tuple(map(int, value.split("."))))


def validate_paired_matrix(runs, before, after):
    """Require all seven real sites, exact paired scenarios, and equal run inputs."""
    configure_versions(before, after)
    if {run["version"] for run in runs} != {before, after} or {run["siteId"] for run in runs} != set(SITE_ORDER):
        raise ValueError("Final report requires the selected release pair and exactly seven expected actual sites")
    seen = set()
    for run in runs:
        if not run.get("productReport", {}).get("available"):
            raise ValueError("Both releases require their actual product exporter in every paired run")
        key = (run["version"], run["siteId"], run["scenario"])
        if key in seen:
            raise ValueError("Duplicate version/site/scenario cannot count as a paired run")
        seen.add(key)
    if len(runs) != 28:
        raise ValueError("Final report requires exactly 28 actual runs")
    for site_id in SITE_ORDER:
        previous = {run["scenario"]: run for run in runs if run["siteId"] == site_id and run["version"] == before}
        current = {run["scenario"]: run for run in runs if run["siteId"] == site_id and run["version"] == after}
        if len(previous) != 2 or previous.keys() != current.keys() or "root" not in previous or not (set(previous) - {"root"}).issubset({"direct", "protected"}):
            raise ValueError("Final report needs root and one matching direct/protected scenario per site and version")
        for scenario in previous:
            for key in ("startURL", "scopeURL", "budget", "parameters"):
                if previous[scenario].get(key) != current[scenario].get(key):
                    raise ValueError("Before/after scenario conditions differ: " + key)
    return True



def validate_site_summary(data, runs):
    if data.get("beforeVersion") != BEFORE_VERSION or data.get("afterVersion") != AFTER_VERSION:
        raise ValueError("Actual-site summary belongs to another release pair")
    reviewable = {"afterContinuesWithVerifiedIdentityAfterOtherRealm401"}
    if data.get("result") not in ("passed", "review") or not data.get("checks") or any(value is not True for key, value in data["checks"].items() if key not in reviewable):
        raise ValueError("Actual-site integrity summary contains an incomplete or failed gate")
    expected = {(run["version"], run["siteId"], run["scenario"]): run["jarSHA256"] for run in runs}
    observed = {}
    for version, stage in data.get("stages", {}).items():
        for row in stage.get("runs", []):
            key = (version, row["siteId"], row["scenario"])
            if key in observed:
                raise ValueError("Actual-site summary duplicates one recorded scenario")
            observed[key] = row["jarSHA256"]
    if observed != expected:
        raise ValueError("Actual-site summary matrix or frozen JAR SHA differs from the source corpus")
    return True



def validate_direct_supplement(runs, paired_runs, proof):
    """Two controlled live runs are evidence outside the 28-run discovery matrix."""
    if len(runs) != 2 or {r["version"] for r in runs} != {BEFORE_VERSION, AFTER_VERSION}:
        raise ValueError("Controlled direct supplement requires one actual run of each selected release")
    previous = next(r for r in runs if r["version"] == BEFORE_VERSION)
    current = next(r for r in runs if r["version"] == AFTER_VERSION)
    if previous.get("siteId") != current.get("siteId") or previous.get("scenario") != current.get("scenario"):
        raise ValueError("Controlled direct runs must use the same site and scenario")
    if any((r["siteId"], r["scenario"]) == (previous["siteId"], previous["scenario"]) for r in paired_runs):
        raise ValueError("Controlled direct supplement must not replace or duplicate a discovery matrix case")
    for key in ("startURL", "scopeURL", "budget", "parameters"):
        if previous.get(key) != current.get(key):
            raise ValueError("Controlled direct before/after inputs differ: " + key)
    hashes = {r["version"]: r["jarSHA256"] for r in paired_runs}
    if any(r["jarSHA256"] != hashes[r["version"]] or not r.get("productReport", {}).get("available") for r in runs):
        raise ValueError("Controlled direct supplement uses a different JAR or lacks a real product export")
    if not proof.get("checks") or any(value is not True for value in proof["checks"].values()) or proof.get("matrixRootDiscoveryComparable") is not False:
        raise ValueError("Controlled direct proof is incomplete or conflates automatic root discovery")
    return True


def navigation_validation(path):
    data = read_json(path)
    if data.get("passed") is not True:
        raise ValueError("Navigation browser evidence must explicitly pass")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-version", default=BEFORE_VERSION)
    parser.add_argument("--after-version", default=AFTER_VERSION)
    parser.add_argument("--auth-root", type=Path, help="Fresh test-login artifacts for this paired run; independent from source metadata")
    parser.add_argument("--supplemental-results", type=Path, help="Explicit two-run controlled direct corpus, separate from the 28 discovery runs")
    parser.add_argument("--supplemental-validation", type=Path, help="Current allowlisted proof for the two controlled direct runs")
    parser.add_argument("--navigation-screenshots", type=Path, help="Explicit matching synthetic Chromium fixture screenshot directory; late/replace PNG only")
    parser.add_argument("--navigation-validation", type=Path, help="Current actual Chromium navigation-fixture validation, separate from seven-site runs")
    parser.add_argument("--results", type=Path, required=True, help="Actual run.json tree")
    parser.add_argument("--metadata-root", type=Path, default=Path("/workspace/simple-sites"))
    parser.add_argument("--out", type=Path, required=True, help="Local raw report destination outside the Git checkout")
    parser.add_argument("--font", type=Path, default=FONT)
    parser.add_argument("--checks", type=Path, help="Current, evidence-backed improvement check records")
    parser.add_argument("--site-validation", type=Path, help="Current allowlisted actual-site validation summary")
    parser.add_argument("--regression-validation", type=Path, help="Current public Simple verification JSON for the same JAR")
    parser.add_argument("--public-summary", type=Path, help="Optional strict allowlisted count-only summary")
    parser.add_argument("--browser-check", action="store_true", help="Check the self-contained HTML in local Chromium/Playwright")
    parser.add_argument("--require-complete-matrix", action="store_true", help="Require seven sites, two paired scenarios and both immutable versions")
    args = parser.parse_args()
    configure_versions(args.before_version, args.after_version)
    out = args.out.resolve()
    if out.is_relative_to(ROOT):
        parser.error("Raw report output must be outside the Git checkout")
    if (out / "SEALED.json").exists():
        parser.error("This local report is sealed; choose a new output directory")
    out.mkdir(parents=True, exist_ok=True)
    (out / "sources").mkdir(exist_ok=True)
    runs = load_runs(args.results.resolve())
    ordered_versions(runs)
    if args.require_complete_matrix:
        validate_paired_matrix(runs, args.before_version, args.after_version)
    supplementary = []
    direct_proof = None
    if args.supplemental_results or args.supplemental_validation:
        if not args.supplemental_results or not args.supplemental_validation:
            parser.error("Controlled direct supplement requires both its recorded corpus and verification")
        supplementary = load_runs(args.supplemental_results.resolve())
        direct_proof = read_json(args.supplemental_validation)
        validate_direct_supplement(supplementary, runs, direct_proof)
    values, summaries, sites = [], [], []
    for run in [*runs, *supplementary]:
        print(json.dumps({"phase": "parse", "version": run["version"], "site": run["siteId"], "scenario": run["scenario"]}), flush=True)
        product = product_values(run)
        supplemental = supplemental_values(run)
        extracted = enrich(product, run) + enrich(additional_values_only(product, supplemental, run), run)
        collection_mode = "supplemental-direct-contrast" if run in supplementary else "paired-root-or-direct-seed"
        for value in extracted:
            value["collectionMode"] = collection_mode
        values.extend(extracted)
        for item in run.get("parsedValues", []):
            values.extend(enrich([item], run))
        summaries.append({key: run.get(key) for key in ("version", "siteId", "scenario", "jarSHA256", "runId", "state", "message", "sent", "completed", "pending", "excluded", "failed", "partial", "katana", "productReport", "transportProtocol", "startedAt", "finishedAt", "budget", "startURL", "scopeURL", "wallLimitReached", "auditStopReason", "parameters")})
        summaries[-1]["collectionMode"] = collection_mode
        summaries[-1]["siteName"] = SITE_NAMES.get(run["siteId"], run["siteId"])
        summaries[-1]["transportResponses"] = sum(1 for r in run.get("transport", []) if int(r.get("status", 0)) > 0)
        summaries[-1]["transportErrors"] = sum(1 for r in run.get("transport", []) if int(r.get("status", 0)) == 0)
        summaries[-1]["transportErrorDetails"] = [{key: r.get(key) for key in ("url", "method", "error", "errorNote")} for r in run.get("transport", []) if int(r.get("status", 0)) == 0]
        summaries[-1]["findingCount"] = len(run.get("findings", []))
        summaries[-1]["findingCategoryCounts"] = dict(collections.Counter(f.get("category", "unknown") for f in run.get("findings", [])))
        summaries[-1]["productExport"] = run.get("_product_export_summary", {"available": False, "reason": run.get("productReport", {}).get("error", "not available")})
        run.pop("_source_cache", None)
    values.extend(test_login_values(args.auth_root or args.metadata_root, sorted({run["siteId"] for run in runs}), out))
    for i, value in enumerate(values, 1):
        value["id"] = f"V{i:07d}"
        value["valueSHA256"] = sha(value["valueExact"].encode("utf-8"))
        value["rawLexemeSHA256"] = sha(str(value.get("rawLexeme", "")).encode("utf-8"))
    for site_id in sorted({run["siteId"] for run in runs}, key=lambda name: (SITE_ORDER.get(name, 99), name)):
        metadata = {}
        for candidate in (args.metadata_root / f"{site_id}-metadata.json", args.metadata_root / site_id / "metadata.json"):
            if candidate.is_file():
                metadata = read_json(candidate)
                break
        sites.append({"id": site_id, "name": SITE_NAMES.get(site_id, site_id), "metadata": metadata})
        if metadata:
            (out / "sources").mkdir(exist_ok=True)
            write_json(out / "sources" / f"{site_id}-metadata.json", metadata)
    checks = read_json(args.checks) if args.checks else []
    if isinstance(checks, dict):
        checks = checks.get("checks", [])
    actual_site_validation = read_json(args.site_validation) if args.site_validation else None
    if actual_site_validation:
        validate_site_summary(actual_site_validation, runs)
        write_json(out / "sources" / "actual-site-validation.json", actual_site_validation)
        if actual_site_validation.get("retryObservations"):
            write_json(out / "sources" / "retry-observations.json", actual_site_validation["retryObservations"])
        env = actual_site_validation.get("automaticEnvDiscovery", {})
        if BEFORE_VERSION in env and AFTER_VERSION in env:
            note = f" 실제 루트 자동 발견의 ENV 내용 성공 응답은 {env[BEFORE_VERSION]['capturedPages']}→{env[AFTER_VERSION]['capturedPages']}건이며 계정별 응답 수로 집계했습니다."
            for check in checks:
                if check.get("id") == "R02":
                    check["detail"] += note
    bundle = {"schemaVersion": 1, "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
              "versions": ordered_versions(runs), "sites": sites, "runs": [r for r in summaries if r["collectionMode"] != "supplemental-direct-contrast"],
              "supplementalRuns": [r for r in summaries if r["collectionMode"] == "supplemental-direct-contrast"], "directSupplementValidation": direct_proof,
              "categories": CATEGORIES, "origins": ORIGINS, "values": values,
              "valueCounts": {"category": dict(collections.Counter(v["category"] for v in values)), "origin": dict(collections.Counter(v["sourceOrigin"] for v in values)),
                              "version": dict(collections.Counter(v["version"] for v in values))}, "checks": checks,
              "actualSiteValidation": actual_site_validation,
              "limitations": ["실제 로컬 원본 앱·HTTP/HTTPS·운영 Runner/Katana 엔진 검증이며 실제 Burp GUI 조작 검증이 아닙니다.",
                              "미확인·부분·실패는 남은 범위입니다. 큐 종료를 사이트 전체 점검 완료로 해석하지 않습니다.",
                              "민감정보·키·토큰은 관측한 원문 값이며 자동으로 인증에 재사용하거나 외부 공급자에서 유효성을 검사하지 않았습니다.",
                              "재시험 보고서의 추가 파싱 결과는 제품이 자동 탐지한 결과로 집계하지 않습니다. 소스맵의 sourcesContent 전체 문자열을 비밀값으로 분류하지 않습니다.",
                              "전후 버전 모두 실제 제품 exporter의 원문 값을 사용하며 제품 탐지·제품 보고서 보충 파싱·독립 보충 파싱을 구분합니다.",
                              "값 수는 원본 위치별 관측 수입니다. 동일한 값의 여러 응답·여러 버전·반복 위치를 취약점 또는 독립 탐지 건수로 합산하지 않습니다.",
                              "파싱 값은 축약하지 않습니다. 지원하지 않는 엔티티는 RAW_LITERAL_UNDECODED로 표시한 원문 리터럴이며 디코딩 완료 값으로 읽지 않습니다. PDF 대표 값 외 전체 항목은 HTML·JSON·CSV·증거 ZIP에 보존합니다."]}
    if direct_proof:
        write_json(out / "sources" / "realm-direct-verification.json", direct_proof)
        bundle["limitations"].append("별도 merchant realm 직접 대조 2회는 본28회와 분리했습니다. root after에서 merchant/me가 자동 발견되지 않아 원래 자동 발견 검증 게이트는 review/false로 유지합니다. 직접 지정 결과를 자동 발견 성공으로 바꾸지 않습니다.")
    if args.regression_validation:
        verification = read_json(args.regression_validation)
        same_jar = {run["jarSHA256"] for run in runs if run["version"] == verification.get("version")}
        if same_jar != {verification.get("jarSha256")} or verification["defaultProfile"].get("failedGroups"):
            raise ValueError("Regression verification does not match this immutable JAR or contains failures")
        bundle["regressionValidation"] = verification
        write_json(out / "sources" / f"verification-simple-{AFTER_VERSION}.json", verification)
    if args.navigation_validation:
        evidence = navigation_validation(args.navigation_validation)
        bundle["navigationValidation"] = evidence
        write_json(out / "sources" / "navigation-validation.json", evidence)
    if args.navigation_screenshots:
        if not args.navigation_validation:
            parser.error("Navigation screenshots require matching navigation validation")
        captures = []
        (out / "navigation-captures").mkdir(exist_ok=True)
        for name, caption in (("late", "1.8초 지연 후 나타난 메뉴를 유지"), ("replace", "화면 동작 전의 최초 링크와 변경 후 메뉴를 함께 유지")):
            source = args.navigation_screenshots / (name + ".png")
            if not source.is_file():
                raise ValueError("Required navigation fixture PNG is missing: " + name)
            relative = "navigation-captures/" + name + ".png"
            shutil.copyfile(source, out / relative)
            captures.append({"file": relative, "caption": caption, "sha256": sha(source.read_bytes()),
                             "scope": "Synthetic loopback fixture; actual Chromium DOM with production helper service/API adapters, not native extension/DNR or seven-site browser coverage"})
        bundle["navigationCaptures"] = captures
    wall_limited = [run for run in summaries if run.get("wallLimitReached")]
    if wall_limited:
        bundle["limitations"].append(f"실제 실행 {len(wall_limited)}개는 시험 하네스의 전체 경과 시간 한도로 중지했습니다. 제품 자동 일시정지와 구분하며 STOPPED·미확인·잔여 큐를 그대로 보존합니다.")
    if any(run["transportErrors"] for run in summaries):
        bundle["limitations"].append("I/O 수신 실패는 실제 응답 수에서 제외합니다. Juice Shop /ftp의 원본 Content-Length/EOF 불일치에 대한 Java HTTP 어댑터 제한 등 개별 사유는 실행 상세에 기록하며 실제 Burp TCP 처리 결과로 일반화하지 않습니다.")
    if actual_site_validation:
        for limitation in actual_site_validation.get("limitations", []):
            if isinstance(limitation, str) and limitation not in bundle["limitations"]:
                bundle["limitations"].append(limitation)
    write_json(out / "report.json", bundle)
    write_json(out / "raw-values.json", values)
    csv_values(out / "raw-values.csv", values)
    html_report(out / "report.html", bundle)
    representatives = build_pdf(out / PDF_NAME, bundle, args.font)
    validation = validate_artifacts(out, values, representatives)
    validation["completePairedMatrixRequiredAndPassed"] = args.require_complete_matrix
    if args.browser_check:
        subprocess.run(["node", str(ROOT / "audit/simple-retest/verify_report_browser.mjs"), str(out)], check=True)
        browser_validation = read_json(out / "browser-validation.json")
        validation["browserValidation"] = {"passed": browser_validation["passed"], "checks": len(browser_validation["checks"]),
                                           "externalRequestCount": browser_validation["externalRequestCount"]}
    write_json(out / "report-validation.json", validation)
    if args.public_summary:
        args.public_summary.parent.mkdir(parents=True, exist_ok=True)
        write_json(args.public_summary, public_summary(bundle))
    parsed_archive = parsed_values_zip(out, bundle)
    archive = evidence_zip(out, [*runs, *supplementary])
    print(json.dumps({"output": str(out), "runs": len(runs), "supplementalRuns": len(supplementary), "sites": len(sites), "exactValues": len(values),
                      "pdfPages": validation["pdfPages"], "zip": archive, "parsedValuesZip": parsed_archive}, ensure_ascii=False))


if __name__ == "__main__":
    main()
