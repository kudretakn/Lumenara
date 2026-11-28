#!/usr/bin/env python3
"""Offline, privacy-limited WP05 report helpers. No device access or acceptance by inference."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics


def apk_identity(path):
    path = Path(path)
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    size = path.stat().st_size
    return {'bytes': size, 'MB': size / 1_000_000, 'MiB': size / 1_048_576,
            'sha256': digest.hexdigest()}


def process_stat(line, ticks):
    # comm can contain spaces and parentheses. Offsets below start at stat field 3.
    fields = line[line.rfind(')') + 2:].split()
    return {'cpu_s': (int(fields[11]) + int(fields[12])) / ticks,
            'birth_uptime_s': int(fields[19]) / ticks}


def frame_summary(text):
    durations = []
    first = []
    header = None
    seen = set()
    for line in text.splitlines():
        cells = line.strip().rstrip(',').split(',')
        if 'IntendedVsync' in cells and 'FrameCompleted' in cells:
            header = {name: index for index, name in enumerate(cells)}
        elif header and len(cells) >= len(header):
            try:
                values = {name: int(cells[index]) for name, index in header.items()}
                # Bit 0 is FIRST_DRAW; it is retained. Other bits are not ordinary app frames.
                if values['Flags'] & ~1:
                    continue
                delta = values['FrameCompleted'] - values['IntendedVsync']
                if delta <= 0 or values['FrameCompleted'] >= 2**63 - 1:
                    continue
                identity = (values['IntendedVsync'], values['FrameCompleted'])
                if identity in seen:
                    continue
                seen.add(identity)
                durations.append(delta / 1_000_000)
                if values['Flags'] & 1:
                    first.append(delta / 1_000_000)
            except (ValueError, KeyError, IndexError):
                continue
    if not durations:
        return {'status': 'unmeasured'}
    ordered = sorted(durations)
    return {'status': 'measured', 'count': len(ordered), 'p50_ms': statistics.median(ordered),
            'p95_ms': ordered[math.ceil(.95 * len(ordered)) - 1], 'max_ms': max(ordered),
            'over_32ms': sum(x > 32 for x in ordered), 'over_50ms': sum(x > 50 for x in ordered),
            'first_draw_status': 'measured' if first else 'unmeasured',
            'first_draw_count': len(first), 'first_draw_max_ms': max(first, default=None)}


def perf_events(text):
    events = []
    for line in text.splitlines():
        match = re.search(r'\b(startup_hub_\w+) process_ms=(\d+) uptime_ms=(\d+) draw_ns=(\d+) frame_vsync_ns=(\d+)', line)
        if match:
            events.append(dict(zip(('event', 'process_ms', 'uptime_ms', 'draw_ns', 'frame_vsync_ns'),
                                   (match[1], *(int(match[i]) for i in range(2, 6))))))
    return events


def presentation_check(events, observation):
    """Independent observer identifies the first complete frame and its actual-vsync timestamp."""
    full = next((event for event in events if event['event'] == 'startup_hub_full_draw'), None)
    if not observation:
        return {'status': 'unmeasured'}
    if not math.isfinite(observation['full_process_ms']) or observation['full_process_ms'] < 0:
        raise ValueError('External presentation time must be finite and nonnegative')
    interval = observation['frame_interval_ns']
    if interval <= 0 or not observation.get('evidence'):
        raise ValueError('External evidence and positive frame interval are required')
    if not full:
        return {'status': 'unmeasured'}
    if full['frame_vsync_ns'] <= 0:
        return {'status': 'unmeasured', 'reason': 'draw occurred without an observed frame callback'}
    delta = abs(full['frame_vsync_ns'] - observation['full_frame_vsync_ns'])
    return {'status': 'pass' if delta <= interval else 'fail', 'difference_frames': delta / interval,
            'external_full_process_ms': observation['full_process_ms'], 'evidence': observation['evidence']}


def cpu15_from_sched(observation):
    """Sum all attributed app-thread running slices, clipped to the exact birth window."""
    birth = observation['process_birth_ns']
    end = birth + 15_000_000_000
    if (observation['capture_start_ns'] > birth or observation['capture_end_ns'] < end
            or observation.get('lost_events') != 0 or not observation.get('all_app_threads')
            or not observation.get('evidence')):
        return {'status': 'unmeasured', 'reason': 'incomplete scheduler coverage/attribution'}
    cpu_ns = 0
    per_thread = {}
    for tid, start, stop in observation['running_slices_ns']:
        if tid not in observation['app_tids'] or start < 0 or start > stop:
            raise ValueError('Invalid/unattributed scheduler slice')
        per_thread.setdefault(tid, []).append((start, stop))
        cpu_ns += max(0, min(stop, end) - max(start, birth))
    for slices in per_thread.values():
        ordered = sorted(slices)
        if any(right[0] < left[1] for left, right in zip(ordered, ordered[1:])):
            raise ValueError('Overlapping/duplicate slices for one thread')
    return {'status': 'pass' if cpu_ns < 5_000_000_000 else 'fail', 'cpu_s': cpu_ns / 1e9,
            'window_start_ns': birth, 'window_end_ns': end, 'actual_window_s': 15,
            'evidence': observation['evidence']}


def report(directory, presentation=None, sched=None):
    directory = Path(directory)
    result = json.loads((directory / 'capture.json').read_text())
    events = perf_events((directory / 'perf.log').read_text())
    result.update(events=events, frames=frame_summary((directory / 'gfxinfo.txt').read_text()),
                  marker_check=presentation_check(events, presentation))
    result['hub_target'] = ('pass' if presentation['full_process_ms'] < 1500 else 'fail') if presentation else 'unmeasured'
    # Rounded process counters over an observed window never silently become exact CPU15.
    result['cpu15_target'] = cpu15_from_sched(sched) if sched else {'status': 'unmeasured',
        'reason': 'exact process-birth [0,15s] scheduler evidence required'}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--presentation', type=Path, help='Independent release frame observation JSON')
    parser.add_argument('--cpu-sched', type=Path, help='Attributed external scheduler slices JSON')
    args = parser.parse_args()
    observation = json.loads(args.presentation.read_text()) if args.presentation else None
    sched = json.loads(args.cpu_sched.read_text()) if args.cpu_sched else None
    output = report(args.directory, observation, sched)
    (args.directory / 'report.json').write_text(json.dumps(output, indent=2) + '\n')
    print(json.dumps(output, indent=2))


if __name__ == '__main__':
    main()
