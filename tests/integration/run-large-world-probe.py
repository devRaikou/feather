#!/usr/bin/env python3
"""Exercise actual Swift chunk loading and Slime save/reopen with a large Anvil map."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--server-jar', type=Path, required=True, help='Swift 1.8.8 server jar')
parser.add_argument('--world', type=Path, required=True, help='Anvil source folder (read only)')
parser.add_argument('--ender-chests', type=int, default=68)
parser.add_argument('--heap', default='3g')
parser.add_argument('--timeout', type=int, default=120)
parser.add_argument('--feather-jar', type=Path, default=Path(__file__).resolve().parents[2]
                    / 'slimeworldmanager-plugin/target/feather-plugin-1.0.jar')
args = parser.parse_args()
server_jar = args.server_jar.resolve(strict=True)
feather_jar = args.feather_jar.resolve(strict=True)
world = args.world.resolve(strict=True)
work = Path(tempfile.mkdtemp(prefix='feather-world-probe-'))
classes = work / 'classes'
classes.mkdir()
plugins = work / 'plugins'
(plugins / 'feather').mkdir(parents=True)


def source_digest():
    digest = hashlib.sha256()
    for path in sorted([world / 'level.dat', *(world / 'region').glob('*.mca')]):
        digest.update(path.name.encode())
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
    return digest.hexdigest()


before = source_digest()
subprocess.run(['javac', '-proc:none', '-cp', str(feather_jar) + ':' + str(server_jar),
                '-d', str(classes), str(Path(__file__).with_name('LazyWorldProbe.java'))], check=True)
(classes / 'plugin.yml').write_text('name: LazyWorldProbe\nversion: 1.0\n'
                                  'main: probe.LazyWorldProbe\ndepend: [feather]\n')
subprocess.run(['jar', 'cf', str(plugins / 'probe.jar'), '-C', str(classes), '.'], check=True)
shutil.copy2(feather_jar, plugins / 'feather.jar')
(work / 'eula.txt').write_text('eula=true\n')
(work / 'server.properties').write_text(
    'server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nlevel-type=FLAT\n'
    'generator-settings=2;0;1\ngenerate-structures=false\nallow-nether=false\n'
    'view-distance=2\nmax-players=0\nspawn-animals=false\nspawn-monsters=false\n')
(work / 'bukkit.yml').write_text('settings:\n  allow-end: false\n')
(plugins / 'feather/main.yml').write_text(
    'enable_async_world_gen: false\nenable_commands: false\nupdater:\n  enabled: false\n')
print('Test artifacts:', work, flush=True)
with (work / 'server-output.log').open('w') as log:
    try:
        subprocess.run(['java', '-Xms256m', '-Xmx' + args.heap, '-Dprobe.map=' + str(world),
                        '-Dprobe.expectedEnders=' + str(args.ender_chests), '-jar', str(server_jar),
                        'nogui', '--nojline', '--noconsole'], cwd=work, stdout=log,
                       stderr=subprocess.STDOUT, timeout=args.timeout, check=True)
    finally:
        if source_digest() != before:
            raise SystemExit('FAIL: source map changed')
result = work / 'probe-result.txt'
if not result.is_file() or not result.read_text().startswith('PASS:'):
    raise SystemExit('FAIL: inspect ' + str(work / 'server-output.log'))
print(result.read_text())
