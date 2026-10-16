"""Stage only the configured DeepSeek ref inside a disposable device's private cache.

No credential value is printed, passed as a process argument, or written to the host.
"""
import argparse
import json
import subprocess
import yaml

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--credential-serial', help='Read the configured ref from this disposable device; default: target device')
args = parser.parse_args()
device = [args.adb, '-s', args.serial, 'shell', 'run-as', 'com.dsh.android']
source = [args.adb, '-s', args.credential_serial or args.serial, 'shell', 'run-as', 'com.dsh.android']
raw = subprocess.check_output(source + ['cat', 'files/dsh/home/.dsh/.credentials.yaml'])
document = yaml.safe_load(raw)
key = document.get('refs', {}).get('DEEPSEEK_API_KEY')
if not isinstance(key, str) or not key:
    raise SystemExit('No DeepSeek credential is configured on this device.')
payload = json.dumps({'apiKey': key}).encode('utf-8')
labels = ['initial', 'settings', 'reloaded', 'allow-pending', 'allow-finished', 'resumed', 'reject-pending', 'reject-finished', 'stop-pending', 'native-menu', 'stopped']
subprocess.run(device + ['rm', '-f', 'cache/live-approval-result.json'] + [f'cache/live-approval-ui-{label}.png' for label in labels], check=True)
subprocess.run(device + ['sh', '-c', '"umask 077; cat > cache/live-approval-input.json"'], input=payload, check=True)
print('Staged the configured DeepSeek credential in private device cache; its value was not logged.')
