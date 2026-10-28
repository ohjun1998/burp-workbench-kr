"""Synthetic metadata only: integrity failures and false-positive semantics, no original secrets."""
import copy
import importlib.util
from pathlib import Path
import unittest

HERE=Path(__file__).resolve().parent
def module(name):
    spec=importlib.util.spec_from_file_location(name,HERE/(name+'.py'))
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

compare_module=module('compare_offline')
prepare_module=module('prepare_corpus')

class ComparisonTest(unittest.TestCase):
    def fixture(self):
        c=dict(caseId='C001',siteId='fixture',status=200,responseSHA256='a'*64,decodedSHA256='b'*64,
            adapterWireMatchesOriginal=True,allExportSourceSpansVerified=True,allExportResponseHashesMatchOriginal=True)
        p=dict(id='POS1',category='SENSITIVE',expectedKind='relevant-value',valueSHA256='c'*64)
        n=dict(id='NEG1',category='TOKEN',expectedKind='domtokenlist-noise',valueSHA256='d'*64)
        def checked(t,matched,present):
            return dict(t,productLocationOrFingerprintMatch=matched,reportExactValueAndRawMatch=present,
                reportExactValueAndSourceSpan=present,
                reportValuePresent=present,exportProvenance=['REPORT_SUPPLEMENT'] if present else [],matchedSemantic=[])
        before=dict(version='1.3.2',networkRequests=0,caseCount=1,cases=[dict(c,checks=[checked(p,False,True),checked(n,False,True)])])
        after=dict(version='1.4.0',networkRequests=0,caseCount=1,cases=[dict(c,checks=[checked(p,True,True),checked(n,False,False)])])
        runtime=dict(exitCode=0,jarUnchanged=True,strictJava17=True,networkRequests=0,jarSHA256='e'*64,corpusSHA256='f'*64,harnessSHA256='1'*64)
        corpus=dict(sites=['fixture'],cases=[dict(c,truth=[p,n])])
        return before,copy.deepcopy(runtime),after,copy.deepcopy(runtime),corpus

    def test_identical_sources_compare_product_and_export_separately(self):
        result=compare_module.compare(*self.fixture())
        self.assertEqual(result['result'],'passed')
        self.assertEqual(result['before']['productLocationOrFingerprintLinked'],0)
        self.assertEqual(result['after']['productLocationOrFingerprintLinked'],1)
        self.assertEqual(result['before']['exactExportValueAndRaw'],1)
        self.assertEqual(result['after']['exactExportValueAndRaw'],1)

    def test_changed_response_sha_is_rejected(self):
        f=self.fixture();f[2]['cases'][0]['responseSHA256']='0'*64
        with self.assertRaisesRegex(ValueError,'SHA'):compare_module.compare(*f)

    def test_missing_version_is_rejected(self):
        f=self.fixture();del f[2]['version']
        with self.assertRaisesRegex(ValueError,'version'):compare_module.compare(*f)

    def test_different_harness_is_rejected(self):
        f=self.fixture();f[3]['harnessSHA256']='0'*64
        with self.assertRaisesRegex(ValueError,'harness'):compare_module.compare(*f)

    def test_missing_truth_check_is_rejected(self):
        f=self.fixture();f[2]['cases'][0]['checks'].pop()
        with self.assertRaisesRegex(ValueError,'missing'):compare_module.compare(*f)

    def test_invalid_actual_export_source_span_keeps_review(self):
        f=self.fixture();f[2]['cases'][0]['allExportSourceSpansVerified']=False
        result=compare_module.compare(*f)
        self.assertEqual(result['result'],'review')
        self.assertFalse(result['gates']['allExportSourceSpansVerified'])

    def test_quote_boundary_change_does_not_invalidate_exact_current_source_span(self):
        f=self.fixture();f[2]['cases'][0]['checks'][0]['reportExactValueAndRawMatch']=False
        result=compare_module.compare(*f)
        self.assertEqual(result['result'],'passed')
        self.assertEqual(result['after']['exactExportValueAndRaw'],0)
        self.assertEqual(result['after']['exactExportValueAndSourceSpan'],1)

    def test_retained_noise_keeps_review(self):
        f=self.fixture();f[2]['cases'][0]['checks'][1]['reportValuePresent']=True
        result=compare_module.compare(*f)
        self.assertEqual(result['result'],'review')
        self.assertEqual(result['remainingNoiseIds'],['NEG1'])

    def test_previously_detected_regression_keeps_review(self):
        f=self.fixture();f[0]['cases'][0]['checks'][0]['productLocationOrFingerprintMatch']=True
        f[2]['cases'][0]['checks'][0]['productLocationOrFingerprintMatch']=False
        result=compare_module.compare(*f)
        self.assertEqual(result['result'],'review')
        self.assertEqual(result['regressedTruthIds'],['POS1'])

    def test_secret_upgrade_covers_key_token_and_sensitive_categories(self):
        for category in ['SECRET','KEY','TOKEN','SENSITIVE']:
            with self.subTest(category=category):
                check=dict(matchedSemantic=[dict(category=category,state='유효성 미검증',severity='중간',classification='SECRET_CANDIDATE')])
                self.assertTrue(compare_module.elevated_secret(check))

    def test_demo_public_cookie_and_personal_data_are_not_secret_upgrade(self):
        for classification in ['EXAMPLE_OR_PLACEHOLDER','PUBLIC_IDENTIFIER','COOKIE_VALUE','PERSONAL_DATA','DYNAMIC_UNRESOLVED']:
            with self.subTest(classification=classification):
                check=dict(matchedSemantic=[dict(category='SECRET',state='유효성 미검증',severity='높음',classification=classification)])
                self.assertFalse(compare_module.elevated_secret(check))

    def test_export_classification_alone_can_expose_bad_secret_promotion(self):
        check=dict(matchedSemantic=[],exportClassifications=['SECRET_CANDIDATE'],exportCategories=['TOKEN'])
        self.assertTrue(compare_module.elevated_secret(check))

    def test_retained_demo_requires_explicit_example_classification(self):
        check=dict(reportValuePresent=True,exportCategories=['TOKEN'],exportClassifications=['UNVERIFIED'])
        self.assertTrue(compare_module.control_classification_problem(check,'explicit-demo-token'))
        check['exportClassifications']=['EXAMPLE_OR_PLACEHOLDER']
        self.assertEqual(compare_module.control_classification_problem(check,'explicit-demo-token'),'')

    def test_header_name_is_not_an_unverified_key_value(self):
        check=dict(reportValuePresent=True,exportCategories=['KEY'],exportClassifications=['UNVERIFIED'])
        self.assertTrue(compare_module.control_classification_problem(check,'auth-header-name'))
        check['exportClassifications']=['SCHEMA_METADATA']
        self.assertEqual(compare_module.control_classification_problem(check,'auth-header-name'),'')

    def test_source_coordinates_use_utf16_after_supplementary_character(self):
        self.assertEqual(prepare_module.utf16_units('😀before'),8)
        self.assertEqual(prepare_module.utf16_units('😀'),2)

    def test_header_source_omits_status_and_preserves_lf_field_coordinates(self):
        raw=b'HTTP/1.1 200\r\nServer: Fixture\r\nX-Test: value\r\n\r\nbody'
        self.assertEqual(prepare_module.source_text(raw,'body','response.headers'),'Server: Fixture\nX-Test: value')

if __name__=='__main__':
    unittest.main()
