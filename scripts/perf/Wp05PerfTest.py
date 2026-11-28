import hashlib
import tempfile
import unittest
from pathlib import Path
from wp05_report import apk_identity, cpu15_from_sched, frame_summary, perf_events, presentation_check, process_stat


class Wp05PerfTest(unittest.TestCase):
    def test_bytes_decimal_binary_and_sha_are_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            data = b'x' * 1_048_576
            path.write_bytes(data)
            result = apk_identity(path)
            self.assertEqual(1_048_576, result['bytes'])
            self.assertEqual(1.048576, result['MB'])
            self.assertEqual(1, result['MiB'])
            self.assertEqual(hashlib.sha256(data).hexdigest(), result['sha256'])

    def test_external_first_heavy_frame_is_included(self):
        snapshot = 'Flags,IntendedVsync,FrameCompleted,\n1,1000000,2401000000,\n0,2500000000,2516000000,'
        result = frame_summary(snapshot + '\n' + snapshot)
        self.assertEqual(2400, result['max_ms'])
        self.assertEqual(2400, result['first_draw_max_ms'])
        self.assertEqual(2400, result['p95_ms'])
        self.assertEqual(2, result['count'])
        self.assertEqual(1, result['over_50ms'])

    def test_marker_comparison_requires_independent_evidence(self):
        events = perf_events('I LumenaraPerf: startup_hub_full_draw process_ms=1500 uptime_ms=9000 draw_ns=9000000000 frame_vsync_ns=8980000000')
        self.assertEqual('unmeasured', presentation_check(events, None)['status'])
        observation = dict(frame_interval_ns=20_000_000, full_frame_vsync_ns=9000000000,
                           full_process_ms=1520, evidence='release atrace + frame annotation')
        self.assertEqual('pass', presentation_check(events, observation)['status'])
        observation['full_frame_vsync_ns'] += 1
        self.assertEqual('fail', presentation_check(events, observation)['status'])
        events[0]['frame_vsync_ns'] = 0
        self.assertEqual('unmeasured', presentation_check(events, observation)['status'])

    def test_process_counters_include_early_cpu_and_record_real_birth(self):
        fields = ['0'] * 20
        fields[11], fields[12], fields[19] = '220', '80', '1000'
        result = process_stat('42 (app (thread)) ' + ' '.join(fields), 100)
        self.assertEqual(dict(cpu_s=3, birth_uptime_s=10), result)

    def test_scheduler_cpu15_clips_end_and_includes_simultaneous_threads(self):
        observation = dict(process_birth_ns=1_000_000_000, capture_start_ns=0,
                           capture_end_ns=20_000_000_000, lost_events=0, all_app_threads=True,
                           evidence='external trace', app_tids=[42, 43], running_slices_ns=[
                               [42, 500_000_000, 2_000_000_000], [43, 1_000_000_000, 3_000_000_000],
                               [42, 15_000_000_000, 18_000_000_000]])
        result = cpu15_from_sched(observation)
        self.assertEqual(4, result['cpu_s'])
        self.assertEqual(15, result['actual_window_s'])
        self.assertEqual('pass', result['status'])
        observation['running_slices_ns'].append([42, 500_000_000, 2_000_000_000])
        with self.assertRaises(ValueError):
            cpu15_from_sched(observation)
        observation['running_slices_ns'].pop()
        observation['lost_events'] = 1
        self.assertEqual('unmeasured', cpu15_from_sched(observation)['status'])
        observation['lost_events'] = 0
        observation['capture_start_ns'] = 1_000_000_001
        self.assertEqual('unmeasured', cpu15_from_sched(observation)['status'])


if __name__ == '__main__':
    unittest.main()
