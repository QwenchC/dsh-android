"""Export only explicitly named synthetic live-test screenshots, without shell redirection."""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--label', required=True, choices=['initial', 'settings', 'reloaded', 'allow-pending', 'allow-finished', 'resumed', 'reject-pending', 'reject-finished', 'stop-pending', 'native-menu', 'stopped'])
parser.add_argument('--output', required=True)
args = parser.parse_args()
name = f'live-approval-ui-{args.label}.png'
device = [args.adb, '-s', args.serial, 'exec-out', 'run-as', 'com.dsh.android']
image = subprocess.check_output(device + ['cat', f'cache/{name}'])
if not image.startswith(b'\x89PNG\r\n\x1a\n'):
    raise SystemExit('Expected a PNG screenshot from the live test.')
out = Path(args.output) / name
out.write_bytes(image)
subprocess.run([args.adb, '-s', args.serial, 'shell', 'run-as', 'com.dsh.android', 'rm', f'cache/{name}'], check=True)
print(f'Exported synthetic UI screenshot: {name}')
