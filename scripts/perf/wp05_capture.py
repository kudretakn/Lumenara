#!/usr/bin/env python3
"""Capture one cold/warm release run without install, clear, catalog changes or instrumentation."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
from wp05_report import apk_identity, process_stat, report


def capture(args):
    output = args.output
    output.mkdir(parents=True, exist_ok=False)
    prefix = ['adb', '-s', args.serial]

    def adb(*command, timeout=15, required=True):
        proc = subprocess.run(prefix + list(command), text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=timeout)
        if required and proc.returncode:
            raise RuntimeError('adb command failed (see device connection/permissions)')
        return proc.stdout.strip() if proc.returncode == 0 else ''

    def shell(*command, **kwargs):
        return adb('shell', *command, **kwargs)

    def safe_log(pid=None):
        # Retain only fixed performance fields. Do not archive general app/device logs.
        lines = adb('logcat', '-d', '-v', 'threadtime', 'LumenaraPerf:I', '*:S').splitlines()
        selected = []
        for line in lines:
            match = re.match(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s+LumenaraPerf\s*:', line)
            if match and (pid is None or match[1] == pid):
                selected.append(line)
        return '\n'.join(selected) + '\n'

    metadata = {'apk': apk_identity(args.apk), 'mode': args.mode, 'label': args.label,
                'catalog_items': args.catalog_items, 'conditions': args.conditions,
                'package': args.package, 'requested_cpu_window_s': 15,
                'capture_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
    metadata['device'] = {key: shell('getprop', prop) for key, prop in (
        ('model', 'ro.product.model'), ('sdk', 'ro.build.version.sdk'), ('abi', 'ro.product.cpu.abilist'))}
    # APK identity must describe the installed bytes, even if the file was renamed on the host.
    installed = shell('pm', 'path', args.package)
    base = next((line.removeprefix('package:') for line in installed.splitlines()
                 if line.startswith('package:') and line.endswith('/base.apk')), None)
    if not base:
        raise RuntimeError('Installed single base APK not found')
    device_hash = shell('sha256sum', base).split()[0]
    if device_hash != metadata['apk']['sha256']:
        raise RuntimeError('Supplied APK SHA differs from installed APK; capture aborted')
    package_info = shell('dumpsys', 'package', args.package)
    metadata['version'] = [line.strip() for line in package_info.splitlines()
                           if re.match(r'\s*(versionCode=|versionName=)', line)]
    # Certificate fingerprint must come from CI/apksigner, never from credentials or arbitrary logs.
    metadata['certificate_sha256'] = args.certificate_sha256
    before_logs = safe_log().splitlines()
    if args.mode == 'cold':
        shell('am', 'force-stop', args.package)
    else:
        if not shell('pidof', args.package, required=False):
            raise RuntimeError('Warm run requires an already running process')
        shell('input', 'keyevent', 'KEYCODE_HOME')
    shell('dumpsys', 'gfxinfo', args.package, 'reset', required=False)
    ticks_text = shell('getconf', 'CLK_TCK', required=False)
    ticks = int(ticks_text) if ticks_text.isdigit() else args.clock_ticks
    cpu = {'status': 'unmeasured', 'reason': 'process counters unavailable'}
    # Launch without -W so host polling need not wait behind the first heavy draw.
    shell('am', 'start', '-n', args.package + '/dev.lumenara.tv.MainActivity')
    started = time.monotonic()
    pid = None
    samples = []
    gfx_snapshots = []
    next_gfx_sample = started
    while time.monotonic() - started < args.seconds:
        pid_text = shell('pidof', args.package, required=False)
        if pid_text:
            candidate = pid_text.split()[0]
            if pid is not None and pid != candidate:
                raise RuntimeError('Process changed during capture')
            pid = candidate
            if time.monotonic() >= next_gfx_sample:
                # Android keeps a bounded frame ring. Save early frames before they are evicted.
                gfx_snapshots.append(shell('dumpsys', 'gfxinfo', args.package, 'framestats'))
                next_gfx_sample = time.monotonic() + .5
            # The device uptime brackets the counter read; record uncertainty rather than host RTT.
            raw = shell('sh', '-c', f"'cat /proc/uptime; cat /proc/{pid}/stat; cat /proc/uptime'", required=False)
            lines = raw.splitlines()
            if ticks and len(lines) == 3 and ')' in lines[1]:
                stat = process_stat(lines[1], ticks)
                stat.update(read_start_uptime_s=float(lines[0].split()[0]),
                            read_end_uptime_s=float(lines[2].split()[0]))
                samples.append(stat)
                age = stat['read_end_uptime_s'] - stat['birth_uptime_s']
                if args.mode == 'cold' and age >= 15:
                    cpu = {'status': 'measured_observed_window', 'origin': 'process_birth',
                           'cpu_s': stat['cpu_s'], 'window_start_s': 0,
                           'window_end_min_s': max(0, stat['read_start_uptime_s'] - stat['birth_uptime_s'] - 1 / ticks),
                           'window_end_max_s': age + .01, 'ticks_per_second': ticks,
                           'cpu_tick_s': 1 / ticks, 'birth_tick_s': 1 / ticks,
                           'uptime_quantization_s': .01}
                    break
        elif pid is not None:
            raise RuntimeError('Process disappeared during capture')
        time.sleep(.05)
    remaining = args.seconds - (time.monotonic() - started)
    if remaining > 0:
        time.sleep(remaining)
    if args.mode == 'warm' and len(samples) >= 2:
        first, last = samples[0], samples[-1]
        cpu = {'status': 'measured_observed_window', 'origin': 'warm_samples',
               'cpu_s': last['cpu_s'] - first['cpu_s'],
               'window_start_min_uptime_s': first['read_start_uptime_s'],
               'window_start_max_uptime_s': first['read_end_uptime_s'] + .01,
               'window_end_min_uptime_s': last['read_start_uptime_s'],
               'window_end_max_uptime_s': last['read_end_uptime_s'] + .01,
               'actual_window_min_s': max(0, last['read_start_uptime_s'] - first['read_end_uptime_s'] - .01),
               'actual_window_max_s': last['read_end_uptime_s'] + .01 - first['read_start_uptime_s'],
               'ticks_per_second': ticks, 'cpu_tick_s': 1 / ticks, 'uptime_quantization_s': .01}
    metadata['cpu'] = cpu
    metadata['process_samples'] = samples
    if pid is None or shell('pidof', args.package, required=False).split() != [pid]:
        raise RuntimeError('Process missing or changed before capture completed')
    metadata['process_id'] = pid
    # onStop emits the in-app release summary; external gfxinfo also includes the initial draw.
    shell('input', 'keyevent', 'KEYCODE_HOME')
    metadata['thermal'] = shell('dumpsys', 'thermalservice', required=False)
    metadata['memory'] = shell('dumpsys', 'meminfo', args.package, required=False)
    gfx_snapshots.append(shell('dumpsys', 'gfxinfo', args.package, 'framestats'))
    metadata['gfxinfo_sampling_interval_s'] = .5
    (output / 'gfxinfo.txt').write_text('\n'.join(gfx_snapshots) + '\n')
    after_logs = safe_log(pid).splitlines()
    # No global logcat clear. Remove the captured pre-run prefix, including repeated event text.
    before_logs = [line for line in before_logs if re.match(
        r'^\S+\s+\S+\s+' + re.escape(pid) + r'\s+\d+\s+[VDIWEF]\s+LumenaraPerf\s*:', line)]
    overlap = 0
    for size in range(min(len(before_logs), len(after_logs)), 0, -1):
        if before_logs[-size:] == after_logs[:size]:
            overlap = size
            break
    (output / 'perf.log').write_text('\n'.join(after_logs[overlap:]) + '\n')
    (output / 'capture.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (output / 'report.json').write_text(json.dumps(report(output), indent=2) + '\n')
    print(f'Capture saved to {output}; external full-frame/CPU15 acceptance remains explicit.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--package', default='dev.lumenara.tv', choices=['dev.lumenara.tv'])
    parser.add_argument('--mode', choices=['cold', 'warm'], required=True)
    parser.add_argument('--label', required=True, help='Revision/CI run label')
    parser.add_argument('--certificate-sha256', required=True)
    parser.add_argument('--catalog-items', required=True, type=int)
    parser.add_argument('--conditions', required=True, help='No secrets: network, cache, display mode, filters')
    parser.add_argument('--clock-ticks', type=int, help='Verified CLK_TCK only, if device getconf is unavailable')
    parser.add_argument('--seconds', type=float, default=18)
    args = parser.parse_args()
    if args.seconds < 16 or (args.clock_ticks is not None and args.clock_ticks <= 0):
        parser.error('Capture must be >=16 seconds; CLK_TCK must be positive')
    capture(args)


if __name__ == '__main__':
    main()
