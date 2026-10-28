#!/usr/bin/env python3
"""Focused report exactness checks; synthetic format fixtures, no target I/O."""
import csv
import importlib.util
import json
import re
import tempfile
import unittest
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("build_report", Path(__file__).with_name("build_report.py"))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class ReportExactnessTest(unittest.TestCase):
    def test_json_offsets_escapes_numeric_lexeme_and_pointer(self):
        text = '{"a/b":{"~token":"한글\\r\\n😀"},"environment":1.00,"flags":[true,null]}'
        values = list(report.json_leaves(text))
        self.assertEqual(values[0]["pointer"], "/a~1b/~0token")
        self.assertEqual(values[0]["parsed"], "한글\r\n😀")
        for value in values:
            self.assertEqual(text[value["offset"]:value["offset"] + value["length"]], value["rawLexeme"])
        self.assertEqual(values[1]["rawLexeme"], "1.00")
        self.assertIs(values[2]["parsed"], True)
        self.assertIsNone(values[3]["parsed"])

    def test_invalid_json_never_yields_partial_scalars(self):
        with self.assertRaises(ValueError):
            list(report.json_leaves('{"token":"fixture-token", broken}'))

    def test_classname_is_not_ssn_and_password_hash_is_sensitive(self):
        self.assertIsNone(report.category_for("className", "i"))
        self.assertEqual(report.category_for("ssn", "123-45-6789"), "sensitive")
        self.assertEqual(report.category_for("userSSN", "123-45-6789"), "sensitive")
        self.assertEqual(report.category_for("passwordHash", "fixture-hash"), "sensitive")

    def test_csv_roundtrip_preserves_formula_multiline_and_long_value(self):
        values = [{"id": "test", "valueExact": "=fixture\r\n한글😀\t" + "x" * 100_000, "source": {"line": 2}}]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "values.csv"
            report.csv_values(path, values)
            with path.open(encoding="utf-8-sig", newline="") as source:
                rows = list(csv.DictReader(source))
            self.assertEqual(rows[0]["valueExact"], values[0]["valueExact"])

    def test_html_script_data_preserves_inert_exact_values(self):
        value = '</script><img src=x onerror="fixture()">\u2028한글😀'
        bundle = {"values": [{"valueExact": value}]}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.html"
            report.html_report(path, bundle)
            text = path.read_text()
            payload = re.search(r'<script id="data" type="application/json">(.*?)</script>', text, re.S)[1]
            self.assertEqual(json.loads(payload), bundle)
            self.assertNotIn("<img src=x", text)

    def test_recorded_paths_cannot_escape_run_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "run"
            root.mkdir()
            (root.parent / "outside.txt").write_text("fixture")
            with self.assertRaises(ValueError):
                report.bounded_path(root, "../outside.txt")

    def test_duplicate_supplement_prefers_exact_product_span_after_astral_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            body = '😀 "fixture-value"'
            (root / "body.txt").write_text(body)
            common = {"responseSHA256": "same", "area": "response.body", "account": "fixture", "url": "http://127.0.0.1/"}
            product = [{"valueExact": "fixture-value", "source": {**common, "offset": 3, "length": 15}}]
            supplement = [{"valueExact": "fixture-value", "source": {**common, "offset": 3, "length": 13, "file": "body.txt"}}]
            self.assertEqual(report.additional_values_only(product, supplement, {"_directory": root}), [])

    def test_overlapping_json_and_api_regex_do_not_duplicate_same_location(self):
        source = {"file": "body.txt", "offset": 0, "length": 8, "account": "fixture"}
        item = {"category": "api", "valueExact": "/api/x", "sourceOrigin": "reportSupplemental", "source": source}
        other = {**item, "source": {**source, "offset": 1, "length": 6, "locator": "API literal"}}
        run = {"version": "fixture", "siteId": "fixture", "scenario": "fixture", "runId": "fixture", "jarSHA256": "fixture"}
        self.assertEqual(len(report.enrich([item, other], run)), 1)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            body = ('const prior=`escaped\\`literal`; client.post(host+`/rest/user/login`,data);'
                    'client.get(`${host}/rest/basket/${id}`);client.get(`${host}/rest/products/search?q=${query}`);')
            (root / "body.txt").write_text(body)
            (root / "response.http").write_bytes(b"HTTP/1.1 200\r\n\r\n" + body.encode())
            fixture = {"_directory": root, "pages": [], "transport": [{"index": 1, "url": "http://127.0.0.1/main.js", "method": "GET", "account": "fixture", "status": 200,
                       "decodedBodyFile": "body.txt", "responseFile": "response.http", "responseHeaders": []}]}
            values = report.supplemental_values(fixture)
            by_value = {value["valueExact"]: value for value in values}
            self.assertIn("/rest/user/login", by_value)
            self.assertIn("${host}/rest/basket/${id}", by_value)
            self.assertIn("${host}/rest/products/search?q=${query}", by_value)
            self.assertEqual(by_value["${host}/rest/basket/${id}"]["valueType"], "RAW_TEMPLATE_INTERPOLATED")
            for value in by_value.values():
                source = value["source"]
                self.assertEqual(body[source["offset"]:source["offset"] + source["length"]], value["rawLexeme"])

    def test_product_values_require_exact_utf16_span_and_response_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            body = '😀 {"email":"fixture@example.invalid"}'
            response = b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + body.encode()
            (root / "response.http").write_bytes(response)
            (root / "body.txt").write_bytes(body.encode())
            raw = '"fixture@example.invalid"'
            offset = len(body[:body.index(raw)].encode("utf-16-le")) // 2
            item = {"category": "SENSITIVE", "name": "email", "value": "fixture@example.invalid", "rawText": raw,
                    "provenance": "PRODUCT_FINDING", "source": {"originalId": "fixture:1", "account": "fixture", "profileRevision": "revision",
                    "responseStatus": 200, "responseSha256": report.sha(response), "area": "DECODED_RESPONSE_BODY", "line": 1,
                    "offset": offset, "length": len(raw), "method": "GET", "url": "http://127.0.0.1/"}}
            path = root / "product-export"
            def save():
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("report.json", json.dumps({"values": [item]}))
            save()
            run = {"_directory": root, "productReport": {"available": True, "directory": "product-export"},
                   "pages": [{"originalId": "fixture:1", "account": "fixture", "profileRevision": "revision", "responseFile": "response.http", "decodedBodyFile": "body.txt"}]}
            values = report.product_values(run)
            self.assertEqual(values[0]["valueExact"], item["value"])
            self.assertTrue(values[0]["source"]["evidenceVerified"])
            item["source"]["offset"] -= 1
            save()
            with self.assertRaises(ValueError):
                report.product_values(run)
            item["source"]["offset"] += 1
            item["source"]["responseSha256"] = "0" * 64
            save()
            with self.assertRaises(ValueError):
                report.product_values(run)

    def test_product_header_uses_utf8_wire_and_normalized_lf_coordinates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            header = "x-note: 한글😀\nx-token: fixture-token"
            response = ("HTTP/1.1 200\r\n" + header.replace("\n", "\r\n") + "\r\n\r\n{}").encode()
            (root / "response.http").write_bytes(response)
            (root / "body.txt").write_text("{}")
            offset = len(header[:header.index("fixture-token")].encode("utf-16-le")) // 2
            item = {"category": "TOKEN", "name": "x-token", "value": "fixture-token", "rawText": "fixture-token", "provenance": "REPORT_SUPPLEMENT",
                    "source": {"originalId": "fixture:1", "account": "fixture", "profileRevision": "revision", "responseStatus": 200,
                               "responseSha256": report.sha(response), "area": "RESPONSE_HEADERS", "line": 2, "offset": offset, "length": 13,
                               "method": "GET", "url": "http://127.0.0.1/"}}
            with zipfile.ZipFile(root / "product-export", "w") as archive:
                archive.writestr("report.json", json.dumps({"values": [item]}))
            run = {"_directory": root, "productReport": {"available": True, "directory": "product-export"},
                   "pages": [{"originalId": "fixture:1", "account": "fixture", "profileRevision": "revision", "responseFile": "response.http", "decodedBodyFile": "body.txt"}]}
            value = report.product_values(run)[0]
            self.assertEqual(value["valueExact"], "fixture-token")
            self.assertTrue(value["source"]["evidenceVerified"])


if __name__ == "__main__":
    unittest.main()
