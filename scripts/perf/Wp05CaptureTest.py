import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import wp05_capture


class Wp05CaptureTest(unittest.TestCase):
    def run_capture(self, counters=True, matching_apk=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'release.apk'
            apk.write_bytes(b'fixture APK')
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            now = [0.0]
            launched = [False]
            commands = []

            def run(command, **kwargs):
                commands.append(command)
                args = command[3:]
                text = ''
                code = 0
                if args[:3] == ['shell', 'pm', 'path']:
                    text = 'package:/data/app/fixture/base.apk'
                elif args[:2] == ['shell', 'sha256sum']:
                    text = (digest if matching_apk else '0' * 64) + ' /data/app/fixture/base.apk'
                elif args[:3] == ['shell', 'getconf', 'CLK_TCK']:
                    text = '100'
                elif args[:2] == ['shell', 'pidof']:
                    text = '42' if launched[0] else ''
                elif args[:3] == ['shell', 'am', 'start']:
                    launched[0] = True
                elif args[:3] == ['shell', 'sh', '-c']:
                    if counters:
                        fields = ['0'] * 20
                        fields[11], fields[12], fields[19] = '250', '125', '1000'
                        text = f'{10 + now[0]:.2f} 0\n42 (fixture) ' + ' '.join(fields) + f'\n{10 + now[0] + .01:.2f} 0'
                    else:
                        code = 1
                elif args[:3] == ['shell', 'dumpsys', 'gfxinfo'] and args[-1] == 'framestats':
                    text = 'Flags,IntendedVsync,FrameCompleted,\n1,1000000,2401000000,'
                elif args[0] == 'logcat' and launched[0]:
                    text = ('11-27 00:00:01.000 999 999 I LumenaraPerf: startup_hub_full_draw process_ms=1 uptime_ms=1 draw_ns=1 frame_vsync_ns=1\n'
                            '11-27 00:00:02.000 42 42 I LumenaraPerf: startup_hub_full_draw process_ms=1600 uptime_ms=11600 draw_ns=11600000000 frame_vsync_ns=11590000000')
                return SimpleNamespace(returncode=code, stdout=text, stderr='')

            args = SimpleNamespace(output=root / 'capture', serial='fixture', apk=apk, mode='cold',
                                   label='fixture revision', catalog_items=39682, conditions='fixture',
                                   package='dev.lumenara.tv', certificate_sha256='fixture', clock_ticks=None,
                                   seconds=18)
            with patch.object(wp05_capture.subprocess, 'run', side_effect=run), \
                    patch.object(wp05_capture.time, 'monotonic', side_effect=lambda: now[0]), \
                    patch.object(wp05_capture.time, 'sleep', side_effect=lambda delay: now.__setitem__(0, now[0] + delay)), \
                    contextlib.redirect_stdout(io.StringIO()):
                if not matching_apk:
                    with self.assertRaisesRegex(RuntimeError, 'SHA differs'):
                        wp05_capture.capture(args)
                    self.assertFalse(any(command[3:6] == ['shell', 'am', 'force-stop'] for command in commands))
                    return None
                wp05_capture.capture(args)
            result = json.loads((args.output / 'report.json').read_text())
            self.assertFalse(any('clear' in command or 'install' in command or 'uninstall' in command for command in commands))
            self.assertEqual(1, len(result['events']))
            self.assertEqual(1600, result['events'][0]['process_ms'])
            self.assertEqual('unmeasured', result['marker_check']['status'])
            self.assertEqual('unmeasured', result['cpu15_target']['status'])
            return result

    def test_cold_capture_records_true_window_and_rejects_early_or_foreign_evidence(self):
        result = self.run_capture()
        self.assertEqual('process_birth', result['cpu']['origin'])
        self.assertEqual(3.75, result['cpu']['cpu_s'])
        self.assertGreaterEqual(result['cpu']['window_end_max_s'], 15)
        self.assertLess(result['cpu']['window_end_max_s'], 15.1)
        self.assertEqual(2400, result['frames']['first_draw_max_ms'])

    def test_denied_process_counters_remain_unmeasured(self):
        result = self.run_capture(counters=False)
        self.assertEqual('unmeasured', result['cpu']['status'])

    def test_installed_apk_mismatch_aborts_before_launch_or_force_stop(self):
        self.run_capture(matching_apk=False)


if __name__ == '__main__':
    unittest.main()
