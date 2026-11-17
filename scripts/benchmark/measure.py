"""Offline preparation/measurement helper. No publishing, dispatch or cluster operations."""
import argparse
import hashlib
import io
import json
import os
import platform
import re
import shutil
import subprocess
import time
import zipfile
from html.parser import HTMLParser
from pathlib import Path

CONTROL = Path(__file__).resolve().parents[2]
LOCK = json.loads((CONTROL / 'scripts/benchmark/conditions.json').read_text())


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def jar_info(path):
    with zipfile.ZipFile(path) as jar:
        names = [x.filename for x in jar.infolist() if not x.is_dir()]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate JAR entry')
        return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'entries': {name: hashlib.sha256(jar.read(name)).hexdigest() for name in sorted(names)}}


class ProfileRows(HTMLParser):
    def __init__(self):
        super().__init__(); self.rows = []; self.row = None; self.cell = None
    def handle_starttag(self, tag, attrs):
        if tag == 'tr': self.row = []
        if tag in ('td', 'th') and self.row is not None: self.cell = ''
    def handle_data(self, data):
        if self.cell is not None: self.cell += data
    def handle_endtag(self, tag):
        if tag in ('td', 'th') and self.cell is not None:
            self.row.append(self.cell.strip()); self.cell = None
        if tag == 'tr' and self.row is not None:
            self.rows.append(self.row); self.row = None


def duration(text):
    matches = re.findall(r'([\d.]+)(ms|[hms])', text.replace(',', ''))
    if not matches: raise ValueError('Unrecognized Gradle duration: ' + text)
    return sum(float(n) * {'h': 3600, 'm': 60, 's': 1, 'ms': .001}[unit] for n, unit in matches)


def test_seconds(profile):
    parser = ProfileRows(); parser.feed(profile.read_text())
    rows = [r for r in parser.rows if len(r) >= 2 and r[0] == ':test']
    if len(rows) != 1: raise ValueError('Expected exactly one :test timing in Gradle profile')
    return duration(rows[0][1])


def timed(command, cwd, output, metric, env=None):
    start = time.perf_counter()
    with output.open('w') as log:
        proc = subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        for line in proc.stdout:
            token = os.environ.get('GPR_TOKEN')
            if token: line = line.replace(token, '***')
            print(line, end='', flush=True); log.write(line)
        code = proc.wait()
    result = {'seconds': time.perf_counter() - start, 'exit_code': code}
    write(metric, result)
    if code: raise RuntimeError(f'Command failed with exit code {code}; see {output.name}')
    return result


def prepare(source, results, method, campaign, trial, phase):
    if method not in ('original', 'reuse'): raise ValueError('Invalid method')
    if not re.fullmatch(r'[A-Za-z0-9-]{1,48}', campaign): raise ValueError('Invalid campaign')
    if not 1 <= trial <= 10: raise ValueError('trial must be 1..10')
    if phase != ('pilot' if trial <= 5 else 'confirm'): raise ValueError('Trial/phase mismatch')
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
    if sha != LOCK['source_sha']: raise ValueError('Wrong application source commit')
    original = (source / 'Dockerfile').read_text()
    optimized = (source / 'Dockerfile.ci').read_text()
    for old, new in [('eclipse-temurin:21-jdk', LOCK['jdk_image']),
                     ('eclipse-temurin:21-jre-alpine', LOCK['jre_image'])]:
        original = original.replace('FROM ' + old, 'FROM ' + new)
        optimized = optimized.replace('FROM ' + old, 'FROM ' + new)
    if 'FROM eclipse-temurin:' in original + optimized: raise ValueError('Unpinned base image')
    results.mkdir(parents=True, exist_ok=True)
    (results / 'Dockerfile.original').write_text(original)
    (results / 'Dockerfile.reuse').write_text(optimized)
    wrapper = (source / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    if f'gradle-{LOCK["gradle_version"]}-bin.zip' not in wrapper: raise ValueError('Wrong Gradle')
    write(results / 'conditions.json', LOCK)
    write(results / 'identity.json', {
        'source_sha': sha, 'control_sha': os.environ.get('GITHUB_SHA'), 'campaign': campaign,
        'trial': trial, 'phase': phase, 'method': method, 'cache': LOCK['cache'],
        'run_id': os.environ.get('GITHUB_RUN_ID'), 'attempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
        'run_url': f'https://github.com/{os.environ.get("GITHUB_REPOSITORY")}/actions/runs/{os.environ.get("GITHUB_RUN_ID")}',
        'runner_os': platform.platform(), 'runner_arch': platform.machine(),
        'runner_image': os.environ.get('ImageOS'), 'runner_image_version': os.environ.get('ImageVersion'),
    })
    for name, command in [('java', ['java', '-version']), ('cpu', ['lscpu']),
                          ('memory', ['free', '-b']), ('docker', ['docker', 'version'])]:
        r = subprocess.run(command, capture_output=True, text=True)
        (results / (name + '.txt')).write_text(r.stdout + r.stderr)
    version = (results / 'java.txt').read_text()
    if '21.0.13' not in version or '+9' not in version: raise ValueError('Unexpected host Java patch/build')


def gradle(source, results):
    timed(['bash', './gradlew', 'build', '--no-daemon', '--console=plain', '--no-build-cache', '--profile'],
          source, results / 'gradle.log', results / 'gradle-time.json')
    profiles = list((source / 'build/reports/profile').glob('*.html'))
    if len(profiles) != 1: raise ValueError('Expected one Gradle profile')
    seconds = test_seconds(profiles[0])
    shutil.copy(profiles[0], results / 'gradle-profile.html')
    write(results / 'test-time.json', {'seconds': seconds, 'scope': 'Gradle :test task; included in gradle-time'})
    jars = list((source / 'build/libs').glob('*.jar'))
    if len(jars) != 1 or jars[0].stat().st_size == 0: raise ValueError('Expected one nonempty tested JAR')
    write(results / 'reference.json', jar_info(jars[0]))


def artifact(source, results, directory):
    start = time.perf_counter(); directory.mkdir(parents=True, exist_ok=True)
    jar = next((source / 'build/libs').glob('*.jar')); shutil.copy(jar, directory / 'app.jar')
    checksum = hashlib.sha256((directory / 'app.jar').read_bytes()).hexdigest()
    (directory / 'app.jar.sha256').write_text(checksum + '  app.jar\n')
    write(results / 'artifact-prepare-time.json', {'seconds': time.perf_counter() - start})


def package(source, results, directory, method, archive):
    if method == 'reuse':
        start = time.perf_counter(); actual = jar_info(directory / 'app.jar')
        if actual != json.loads((results / 'reference.json').read_text()): raise ValueError('Artifact differs from tested JAR')
        expected = (directory / 'app.jar.sha256').read_text().split()[0]
        if expected != actual['sha256']: raise ValueError('Checksum mismatch')
        write(results / 'artifact-check-time.json', {'seconds': time.perf_counter() - start})
    write(results / 'image-runner.json', {'runner_os': platform.platform(), 'runner_arch': platform.machine(), 'runner_image': os.environ.get('ImageOS'), 'runner_image_version': os.environ.get('ImageVersion'), 'cpu': subprocess.check_output(['lscpu'], text=True), 'memory': subprocess.check_output(['free', '-b'], text=True)})
    archive.parent.mkdir(parents=True, exist_ok=True)
    command = ['docker', 'buildx', 'build', '--progress=plain', '--no-cache', '--pull',
               '--platform', ','.join(LOCK['platforms']), '--provenance=false', '--sbom=false',
               '--file', str(results / ('Dockerfile.original' if method == 'original' else 'Dockerfile.reuse')),
               '--output', f'type=oci,dest={archive},compression=gzip']
    if method == 'original':
        if not os.environ.get('GPR_TOKEN') or not os.environ.get('GITHUB_ACTOR'): raise ValueError('Existing package read credentials unavailable')
        command += ['--secret', 'id=gpr_actor,env=GITHUB_ACTOR', '--secret', 'id=gpr_token,env=GPR_TOKEN']
    command += [str(source if method == 'original' else directory)]
    timed(command, source, results / 'docker-build.log', results / 'docker-time.json')
    for name, args in [('buildx-version', ['docker', 'buildx', 'version']),
                       ('builder', ['docker', 'buildx', 'inspect'])]:
        r = subprocess.run(args, capture_output=True, text=True)
        (results / (name + '.txt')).write_text(r.stdout + r.stderr)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=['prepare', 'gradle', 'artifact', 'package', 'cleanup'])
    parser.add_argument('--method', choices=['original', 'reuse'], required=True)
    parser.add_argument('--source', type=Path, required=True); parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--artifact', type=Path, required=True); parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--campaign', default=''); parser.add_argument('--trial', type=int, default=0); parser.add_argument('--phase', default='')
    a = parser.parse_args()
    # No subprocess is executed on module import, including tests.
    if a.stage == 'cleanup':
        a.archive.unlink(missing_ok=True)
        return
    a.results.mkdir(parents=True, exist_ok=True)
    try:
        if a.stage == 'prepare': prepare(a.source, a.results, a.method, a.campaign, a.trial, a.phase)
        elif a.stage == 'gradle': gradle(a.source, a.results)
        elif a.stage == 'artifact': artifact(a.source, a.results, a.artifact)
        elif a.stage == 'package': package(a.source, a.results, a.artifact, a.method, a.archive)
    except Exception as error:
        write(a.results / ('error-' + a.stage + '.json'), {'stage': a.stage, 'error': str(error)})
        raise


if __name__ == '__main__': main()
