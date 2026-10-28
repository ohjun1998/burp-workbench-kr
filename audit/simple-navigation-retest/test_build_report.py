#!/usr/bin/env python3
"""Focused report exactness checks; synthetic format fixtures, no target I/O."""
import csv
import copy
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

    def test_product_dynamic_template_keeps_original_span_and_unresolved_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raw = "${host}/api/orders/${id}"
            body = "😀 fetch(`" + raw + "`);"
            response = b"HTTP/1.1 200 OK\r\n\r\n" + body.encode()
            (root / "response.http").write_bytes(response)
            (root / "body.txt").write_bytes(body.encode())
            offset = len(body[:body.index("`" + raw)].encode("utf-16-le")) // 2
            item = {"category": "API", "name": "API template", "value": raw, "rawText": "`" + raw + "`",
                    "valueType": "RAW_TEMPLATE_INTERPOLATED", "classification": "DYNAMIC_UNRESOLVED",
                    "attributes": {"resolution": "DYNAMIC_UNRESOLVED", "httpMethod": "GET"}, "provenance": "REPORT_SUPPLEMENT",
                    "source": {"originalId": "fixture:1", "account": "fixture", "profileRevision": "revision",
                               "responseStatus": 200, "responseSha256": report.sha(response), "area": "DECODED_RESPONSE_BODY", "line": 1,
                               "offset": offset, "length": len(raw) + 2, "method": "GET", "url": "http://127.0.0.1/bundle.js"}}
            with zipfile.ZipFile(root / "product-export", "w") as archive:
                archive.writestr("report.json", json.dumps({"values": [item]}))
            run = {"_directory": root, "productReport": {"available": True, "directory": "product-export"},
                   "pages": [{"originalId": "fixture:1", "account": "fixture", "profileRevision": "revision",
                              "responseFile": "response.http", "decodedBodyFile": "body.txt"}]}
            actual = report.product_values(run)[0]
            self.assertEqual(actual["valueExact"], raw)
            self.assertEqual(actual["rawLexeme"], "`" + raw + "`")
            self.assertEqual(actual["classification"], "DYNAMIC_UNRESOLVED")
            self.assertIn("식 평가 없음", actual["parsingState"])
            self.assertTrue(actual["source"]["evidenceVerified"])

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


class ReleasePairTest(unittest.TestCase):
    def setUp(self):
        self.runs = [dict(version=version, siteId=site, scenario=scenario,
                          startURL="http://127.0.0.1/" + scenario, scopeURL="http://127.0.0.1/", budget=200,
                          parameters={"depth": 2, "profileIds": ["alice", "bob"]}, productReport={"available": True}, jarSHA256=("a" if version == "1.3.1" else "b") * 64)
                     for version in ("1.3.1", "1.3.2") for site in report.SITE_ORDER for scenario in ("root", "direct")]

    def tearDown(self):
        report.configure_versions("1.3.1", "1.3.2")

    def test_complete_new_pair_is_accepted_and_sets_artifact_identity(self):
        self.assertTrue(report.validate_paired_matrix(self.runs, "1.3.1", "1.3.2"))
        self.assertEqual(report.PDF_NAME, "Workbench_Simple_1.3.2_Retest_KR.pdf")

    def test_version_identity_rejects_relabeled_historical_matrix(self):
        for run in self.runs:
            run["version"] = "1.3.0" if run["version"] == "1.3.1" else "1.3.1"
        with self.assertRaises(ValueError):
            report.validate_paired_matrix(self.runs, "1.3.1", "1.3.2")

    def test_releases_require_increasing_numeric_semver(self):
        for before, after in [("1.3.2", "1.3.2"), ("1.3.2", "1.3.1"), ("../1.3.1", "1.3.2")]:
            with self.assertRaises(ValueError):
                report.configure_versions(before, after)
        report.configure_versions("1.3.9", "1.3.10")
        self.assertEqual(report.AFTER_VERSION, "1.3.10")

    def test_report_version_order_is_numeric_and_never_admits_an_unselected_release(self):
        report.configure_versions("1.3.9", "1.3.10")
        self.assertEqual(report.ordered_versions([{"version": "1.3.10"}, {"version": "1.3.9"}]), ["1.3.9", "1.3.10"])
        with self.assertRaises(ValueError):
            report.ordered_versions([{"version": "1.3.8"}])

    def test_duplicate_case_cannot_replace_missing_actual_run(self):
        self.runs[-1] = dict(self.runs[-2])
        with self.assertRaises(ValueError):
            report.validate_paired_matrix(self.runs, "1.3.1", "1.3.2")

    def test_every_paired_run_requires_its_real_product_exporter(self):
        self.runs[-1]["productReport"] = {"available": False}
        with self.assertRaises(ValueError):
            report.validate_paired_matrix(self.runs, "1.3.1", "1.3.2")

    def test_site_integrity_summary_must_match_each_recorded_frozen_jar(self):
        summary = {"beforeVersion": "1.3.1", "afterVersion": "1.3.2", "result": "passed", "checks": {"allRunsPresent": True},
                   "stages": {version: {"runs": [{key: run[key] for key in ("siteId", "scenario", "jarSHA256")} for run in self.runs if run["version"] == version]}
                              for version in ("1.3.1", "1.3.2")}}
        self.assertTrue(report.validate_site_summary(summary, self.runs))
        summary["result"] = "review"
        summary["checks"]["afterContinuesWithVerifiedIdentityAfterOtherRealm401"] = False
        self.assertTrue(report.validate_site_summary(summary, self.runs))
        summary["checks"]["allRunsPresent"] = False
        with self.assertRaises(ValueError):
            report.validate_site_summary(summary, self.runs)
        summary["checks"]["allRunsPresent"] = True
        summary["stages"]["1.3.2"]["runs"][0]["jarSHA256"] = "c" * 64
        with self.assertRaises(ValueError):
            report.validate_site_summary(summary, self.runs)

    def test_parameters_and_selected_profile_ids_must_match(self):
        self.runs[-1]["parameters"] = {"depth": 2, "profileIds": ["anonymous"]}
        with self.assertRaises(ValueError):
            report.validate_paired_matrix(self.runs, "1.3.1", "1.3.2")

    def test_seven_arbitrary_or_unmatched_sites_do_not_pass(self):
        for run in self.runs:
            if run["siteId"] == "loop":
                run["siteId"] = "unexecuted-fixture"
        with self.assertRaises(ValueError):
            report.validate_paired_matrix(self.runs, "1.3.1", "1.3.2")

    def direct_pair(self):
        direct = [copy.deepcopy(run) for run in self.runs if run["siteId"] == "vulnbank" and run["scenario"] == "root"]
        for run in direct:
            run["scenario"] = "realm-direct"
            run["startURL"] = "http://127.0.0.1/api/v1/merchants/me"
            run["budget"] = 30
        return direct, {"checks": {"identityRecheckObserved": True}, "matrixRootDiscoveryComparable": False}

    def test_controlled_direct_pair_is_separate_from_main_discovery_matrix(self):
        direct, proof = self.direct_pair()
        self.assertTrue(report.validate_direct_supplement(direct, self.runs, proof))
        self.assertEqual(len(self.runs), 28)

    def test_controlled_direct_pair_rejects_another_jar(self):
        direct, proof = self.direct_pair()
        direct[1]["jarSHA256"] = "c" * 64
        with self.assertRaises(ValueError):
            report.validate_direct_supplement(direct, self.runs, proof)

    def test_controlled_direct_pair_rejects_changed_parameters(self):
        direct, proof = self.direct_pair()
        direct[1]["parameters"]["profileIds"] = ["anonymous"]
        with self.assertRaises(ValueError):
            report.validate_direct_supplement(direct, self.runs, proof)

    def test_direct_proof_never_claims_automatic_root_discovery_or_missing_success(self):
        direct, proof = self.direct_pair()
        proof["matrixRootDiscoveryComparable"] = True
        with self.assertRaises(ValueError):
            report.validate_direct_supplement(direct, self.runs, proof)
        proof["matrixRootDiscoveryComparable"] = False
        proof["checks"]["identityRecheckObserved"] = False
        with self.assertRaises(ValueError):
            report.validate_direct_supplement(direct, self.runs, proof)

    def test_failed_browser_validation_is_never_presented_as_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "browser.json"
            path.write_text(json.dumps({"passed": False, "checks": []}))
            with self.assertRaises(ValueError):
                report.navigation_validation(path)


if __name__ == "__main__":
    unittest.main()
