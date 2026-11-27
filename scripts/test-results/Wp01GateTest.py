"""Run without Gradle: python3 -m unittest discover -s scripts/test-results -p 'Wp01*Test.py' -v."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from Wp01DeviceTests import Deadline, final_verdict, parse_instrumentation
from Wp01ReleaseGate import (EXPECTED_CERT_SHA256, ReleaseError, check_certificate,
                             check_ci, check_run, pages, select_run)

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
FIXTURES = json.loads((HERE / 'Wp01Fixtures.json').read_text())
PWSH = os.environ.get('WP01_PWSH') or shutil.which('pwsh')


class Wp01ProtocolTest(unittest.TestCase):
    def test_acceptance_fixtures(self):
        for name, fixture in FIXTURES.items():
            with self.subTest(name=name):
                inventory = parse_instrumentation(fixture['inventory']['text'], fixture['inventory']['exit_code'])
                attempts = [parse_instrumentation(a['text'], a['exit_code']) for a in fixture['attempts']]
                if name == 'hung_process' or 'artifact_failure' in fixture:
                    continue  # Real timeouts and artifact faults are covered by launcher tests.
                for attempt in attempts[1:]:
                    attempt['expected'] = list(attempt['results'])
                _, errors = final_verdict(inventory, attempts, fixture['allowed_skips'])
                self.assertEqual(not errors, fixture['success'], errors)

    def test_terminal_bundle_cannot_reuse_previous_identity(self):
        text = FIXTURES['pass']['attempts'][0]['text']
        parsed = parse_instrumentation(text + 'INSTRUMENTATION_STATUS_CODE: 0\n', 0)
        self.assertTrue(any('valid class/test' in error for error in parsed['errors']))

    def test_continuation_cannot_erase_crash(self):
        inventory = parse_instrumentation(FIXTURES['allowed_skip']['inventory']['text'], 0)
        first = parse_instrumentation(FIXTURES['missing_finish']['attempts'][0]['text'], 1)
        second = parse_instrumentation(FIXTURES['pass']['attempts'][0]['text'].replace('test=one', 'test=two'), 0)
        second['expected'] = ['dev.lumenara.tv.Wp01FixtureTest#two']
        final, errors = final_verdict(inventory, [first, second], {})
        self.assertEqual(set(final.values()), {'PASS'})
        self.assertTrue(errors)

    def test_deadline_retries_share_budget_and_preserve_artifact_reserve(self):
        with patch('Wp01DeviceTests.time.monotonic', side_effect=[100, 110, 125, 190, 190]):
            deadline = Deadline(100, 20)
            self.assertEqual(deadline.remaining(), 70)
            self.assertEqual(deadline.remaining(), 55)
            self.assertEqual(deadline.remaining(), 0)
            self.assertEqual(deadline.remaining(artifacts=True), 10)


class Wp01LauncherTest(unittest.TestCase):
    def launch(self, name, powershell=False, hang_artifacts=False):
        with tempfile.TemporaryDirectory(prefix='Wp01 fixture ') as directory:
            temp = Path(directory)
            adb = temp / 'fake adb'
            # Keep the helper executable portable without invoking any real adb.
            adb.write_text('#!' + sys.executable + '\n' + (HERE / 'Wp01FakeAdb.py').read_text())
            adb.chmod(0o755)
            allowed = temp / 'allowed skips.json'
            allowed.write_text(json.dumps(FIXTURES[name]['allowed_skips']))
            out = temp / 'test output'
            state = temp / 'calls.jsonl'
            env = dict(os.environ, WP01_FIXTURE_FILE=str(HERE / 'Wp01Fixtures.json'),
                       WP01_FIXTURE_NAME=name, WP01_FAKE_STATE=str(state), WP01_PYTHON=sys.executable)
            if hang_artifacts:
                env['WP01_HANG_ARTIFACTS'] = '1'
            total = '8' if name == 'hung_process' else '20'
            reserve = '2' if name == 'hung_process' else '3'
            if powershell:
                argv = [PWSH, '-NoProfile', '-File', str(ROOT / 'scripts/local-device-tests.ps1'),
                        '-SkipBuild', '-Out', str(out), '-Adb', str(adb), '-Python', sys.executable,
                        '-Class', 'dev.lumenara.tv.Wp01FixtureTest',
                        '-AllowedSkips', str(allowed), '-Apk', str(temp / 'QA app.apk'),
                        '-TestApk', str(temp / 'QA tests.apk'), '-Package', 'dev.lumenara.tv.qa',
                        '-Runner', 'dev.lumenara.tv.qa.test/androidx.test.runner.AndroidJUnitRunner',
                        '-TotalTimeoutSeconds', total, '-ArtifactReserveSeconds', reserve]
            else:
                argv = ['bash', str(ROOT / 'scripts/ci-device-tests.sh'), '.', str(out),
                        '--serial', 'emulator-5580', '--adb', str(adb), '--allowed-skips', str(allowed),
                        '--class', 'dev.lumenara.tv.Wp01FixtureTest',
                        '--apk', str(temp / 'QA app.apk'), '--test-apk', str(temp / 'QA tests.apk'),
                        '--package', 'dev.lumenara.tv.qa',
                        '--runner', 'dev.lumenara.tv.qa.test/androidx.test.runner.AndroidJUnitRunner',
                        '--total-timeout', total, '--artifact-reserve', reserve]
            start = time.monotonic()
            result = subprocess.run(argv, env=env, capture_output=True, text=True, timeout=15)
            elapsed = time.monotonic() - start
            self.assertEqual(result.returncode == 0, FIXTURES[name]['success'], result.stdout + result.stderr)
            report = json.loads((out / 'Wp01-results.json').read_text())
            self.assertEqual(report['success'], FIXTURES[name]['success'])
            summary = (out / 'summary.txt').read_text()
            if name == 'only_retried':
                self.assertIn('RETRIED  ', summary)
                self.assertNotIn('PASS  ', summary)
            if name == 'allowed_skip':
                self.assertIn('SKIP  ', summary)
                self.assertIn('(allowed: ', summary)
            calls = [json.loads(line) for line in state.read_text().splitlines()]
            self.assertIn(['-s', 'emulator-5580', 'install', '-r', '-g', str(temp / 'QA app.apk')], calls)
            self.assertTrue(any('/sdcard/Android/data/dev.lumenara.tv.qa/files/' in call for call in calls))
            self.assertTrue(any('dev.lumenara.tv.qa.test/androidx.test.runner.AndroidJUnitRunner' in call for call in calls))
            self.assertTrue(any(call[-3:-1] == ['class', 'dev.lumenara.tv.Wp01FixtureTest'] for call in calls))
            if name == 'hung_process':
                self.assertLess(elapsed, 12)
                self.assertLess(report['elapsed_seconds'], 8.5)
                self.assertTrue(any(c['name'] == 'logcat' for c in report['commands']))
            return {key: report[key] for key in ('success', 'results', 'errors')}

    def test_bash_acceptance_fixtures(self):
        for name in FIXTURES:
            with self.subTest(name=name):
                self.launch(name)

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; set WP01_PWSH to run both launchers')
    def test_bash_powershell_same_verdict_and_results(self):
        for name in FIXTURES:
            with self.subTest(name=name):
                self.assertEqual(self.launch(name), self.launch(name, powershell=True))

    def test_hung_artifact_collection_respects_total_deadline(self):
        self.launch('hung_process', hang_artifacts=True)

    def test_non_emulator_rejected_before_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(['bash', str(ROOT / 'scripts/ci-device-tests.sh'), '.', directory,
                                     '--serial', '192.168.1.14:5555'], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('non-emulator', result.stderr)


class Wp01ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.sha = 'a' * 40
        self.run = dict(id=10, run_number=3, run_attempt=1, head_sha=self.sha, head_branch='main', event='push',
                        head_repository={'full_name': 'owner/repo'}, status='completed', conclusion='success')
        self.jobs = [dict(name=name, status='completed', conclusion='success') for name in ('build', 'device')]

    def test_original_certificate_only(self):
        good = 'Signer #1 certificate SHA-256 digest: ' + EXPECTED_CERT_SHA256 + '\n'
        check_certificate(good)
        for bad in ('', good.replace(EXPECTED_CERT_SHA256, 'b' * 64), good + good.replace('#1', '#2')):
            with self.subTest(bad=bad), self.assertRaises(ReleaseError):
                check_certificate(bad)

    def test_requires_exact_main_push_sha(self):
        self.assertEqual(select_run([self.run], self.sha, 'owner/repo'), self.run)
        for key, value in [('head_sha', 'b' * 40), ('head_branch', 'feature'), ('event', 'pull_request'),
                           ('head_repository', {'full_name': 'fork/repo'})]:
            changed = dict(self.run, **{key: value})
            with self.subTest(key=key), self.assertRaises(ReleaseError):
                select_run([changed], self.sha, 'owner/repo')

    def test_new_failed_or_pending_run_cannot_hide_behind_old_green(self):
        for state in ('failure', 'cancelled', None):
            latest = dict(self.run, id=11, run_number=4, conclusion=state)
            selected = select_run([latest, self.run], self.sha, 'owner/repo')
            with self.subTest(state=state), self.assertRaises(ReleaseError):
                check_run(selected, self.jobs)

    def test_requires_build_device_and_every_job_green(self):
        check_run(self.run, self.jobs)
        for jobs in ([], self.jobs[:1], self.jobs + [dict(name='qa', status='completed', conclusion='skipped')],
                     [self.jobs[0], dict(name='device', status='completed', conclusion='failure')]):
            with self.subTest(jobs=jobs), self.assertRaises(ReleaseError):
                check_run(self.run, jobs)

    def test_sha_outside_main_rejected_before_api(self):
        with patch('Wp01ReleaseGate.output', return_value=self.sha), patch('Wp01ReleaseGate.subprocess.run') as git:
            git.return_value.returncode = 1
            with patch('Wp01ReleaseGate.api') as api, self.assertRaises(ReleaseError):
                check_ci(self.sha, 'owner/repo')
            api.assert_not_called()

    def test_rerun_during_gate_check_rejected(self):
        with patch('Wp01ReleaseGate.output', return_value=self.sha), patch('Wp01ReleaseGate.subprocess.run') as git:
            git.return_value.returncode = 0
            with patch('Wp01ReleaseGate.pages', side_effect=[[self.run], self.jobs]), \
                 patch('Wp01ReleaseGate.api', return_value=dict(self.run, run_attempt=2)), \
                 self.assertRaises(ReleaseError):
                check_ci(self.sha, 'owner/repo')


    def test_jobs_on_later_pages_cannot_be_ignored(self):
        first = self.jobs + [dict(name='qa-' + str(i), status='completed', conclusion='success') for i in range(98)]
        last = [dict(name='qa-api28', status='completed', conclusion='failure')]
        with patch('Wp01ReleaseGate.api', side_effect=[{'jobs': first}, {'jobs': last}]) as api:
            jobs = pages('repos/owner/repo/actions/runs/10/jobs?filter=latest', 'jobs')
            self.assertEqual(api.call_count, 2)
            with self.assertRaises(ReleaseError):
                check_run(self.run, jobs)

    @unittest.skipUnless(PWSH, 'PowerShell unavailable')
    def test_local_verification_checks_native_exit_and_certificate_pin(self):
        with tempfile.TemporaryDirectory(prefix='Wp01 certificate ') as folder:
            temp = Path(folder)
            tool = temp / 'fake apksigner'
            tool.write_text('#!' + sys.executable + '\nimport os, sys\n'
                            'print("Signer #1 certificate SHA-256 digest: " + os.environ["WP01_CERT_DIGEST"])\n'
                            'sys.exit(int(os.environ["WP01_CERT_EXIT"]))\n')
            tool.chmod(0o755)
            apk = temp / 'CI release.apk'
            apk.write_bytes(b'fixture unchanged')
            for digest, code, success in ((EXPECTED_CERT_SHA256, 0, True), ('b' * 64, 0, False),
                                          (EXPECTED_CERT_SHA256, 1, False)):
                with self.subTest(digest=digest, code=code):
                    env = dict(os.environ, WP01_CERT_DIGEST=digest, WP01_CERT_EXIT=str(code))
                    result = subprocess.run([PWSH, '-NoProfile', '-File', str(ROOT / 'scripts/sign-release.ps1'),
                                             '-Apk', str(apk), '-SignTool', str(tool), '-Python', sys.executable],
                                            env=env, capture_output=True, text=True, timeout=15)
                    self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
                    self.assertEqual(apk.read_bytes(), b'fixture unchanged')

    @unittest.skipUnless(PWSH, 'PowerShell unavailable')
    def test_local_signing_and_key_creation_disabled(self):
        for extra in ([], ['-CreateKey']):
            result = subprocess.run([PWSH, '-NoProfile', '-File', str(ROOT / 'scripts/sign-release.ps1')] + extra,
                                    capture_output=True, text=True, timeout=15)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Local release signing/key creation is disabled', result.stderr)


if __name__ == '__main__':
    unittest.main()
