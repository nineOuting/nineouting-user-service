"""Unit fixtures only: no real builds, credentials, network, or experiment CSV."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

import measure
import verify_image
import collect


def jar_bytes(content):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as z: z.writestr('BOOT-INF/classes/Fixture.class', content)
    return out.getvalue()


def archive(path, jar, architectures=('amd64', 'arm64')):
    members = {}
    def put(data):
        digest = hashlib.sha256(data).hexdigest(); members['blobs/sha256/' + digest] = data
        return {'mediaType': 'application/vnd.oci.image.manifest.v1+json', 'digest': 'sha256:' + digest, 'size': len(data)}
    descriptors = []
    for arch in architectures:
        layer = io.BytesIO()
        with tarfile.open(fileobj=layer, mode='w:gz') as tar:
            info = tarfile.TarInfo('app/app.jar'); info.size = len(jar); tar.addfile(info, io.BytesIO(jar))
        config = {'architecture': arch, 'os': 'linux', 'config': {'User': 'app', 'WorkingDir': '/app',
            'Entrypoint': ['java','-jar','app.jar'], 'ExposedPorts': {'8081/tcp': {}},
            'Healthcheck': {'Test': ['CMD-SHELL','wget -q --spider http://localhost:8081/actuator/health || exit 1'],
                'Interval': 10_000_000_000, 'Timeout': 5_000_000_000, 'StartPeriod': 30_000_000_000, 'Retries':10}}}
        descriptors.append(put(json.dumps({'schemaVersion':2,'config':put(json.dumps(config).encode()),'layers':[put(layer.getvalue())]}).encode()))
    members['index.json'] = json.dumps({'schemaVersion':2,'manifests':descriptors}).encode()
    with tarfile.open(path, 'w') as tar:
        for name, data in members.items():
            info=tarfile.TarInfo(name); info.size=len(data); tar.addfile(info,io.BytesIO(data))


class BenchmarkTests(unittest.TestCase):
    def test_profile_extracts_test_task_not_build_total(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'profile.html'
            path.write_text('<table><tr><td>:compileJava</td><td>2m3.4s</td></tr><tr><td>:test</td><td class="numeric">4.25s</td></tr></table>')
            self.assertEqual(measure.test_seconds(path),4.25)
            self.assertEqual(measure.duration('1m2.5s'),62.5)
            path.write_text('<table></table>')
            with self.assertRaises(ValueError): measure.test_seconds(path)

    def test_both_platform_jars_verified_and_tampering_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);jar=p/'app.jar';jar.write_bytes(jar_bytes(b'fixture'))
            reference=p/'ref.json';reference.write_text(json.dumps(measure.jar_info(jar)))
            oci=p/'image.tar';archive(oci,jar.read_bytes())
            self.assertEqual(len(verify_image.verify(oci,reference)),2)
            archive(oci,jar_bytes(b'tampered'))
            with self.assertRaises(ValueError): verify_image.verify(oci,reference)
            archive(oci,jar.read_bytes(),('amd64',))
            with self.assertRaises(ValueError): verify_image.verify(oci,reference)

    def test_missing_metrics_not_reported_as_zero(self):
        summary=collect.summarize([{'cache':'cold','method':'original','success':False}])
        old=next(g for g in summary['groups'] if g['method']=='original')
        self.assertEqual(old['success_rate'],0)
        self.assertIsNone(old['metrics']['comparable_ci_seconds']['mean'])
        self.assertIsNone(summary['improvements'][0]['reduction_percent'])

    def test_report_does_not_invent_missing_measurements(self):
        with tempfile.TemporaryDirectory() as tmp:
            summary=collect.summarize([])
            summary.update(completed_expected_20=False, environment_review_required=False, pilot_gate_passed=False)
            output=Path(tmp)/'fixture-report.md'
            collect.report(output,[],summary)
            self.assertIn('20회 완료 조건 충족: False',output.read_text())

    def test_recipe_does_not_reintroduce_deployment_or_shared_caches(self):
        text=(measure.CONTROL/'.github/workflows/scrum-287-benchmark.yml').read_text()
        self.assertIn('workflow_dispatch:',text)
        self.assertNotIn('  push:',text);self.assertNotIn('pull_request:',text)
        for unsafe in ['aws-actions/', 'docker/login-action', 'DEV_GITOPS_TOKEN', 'id-token:', 'packages: write', 'cache-from:', 'cache-to:']:
            self.assertNotIn(unsafe,text)
        original=(measure.CONTROL/'.github/workflows/ci.yml').read_text()
        self.assertIn('name: CI',original)
        self.assertIn('  image:',original)
        helper=(measure.CONTROL/'scripts/benchmark/measure.py').read_text()
        self.assertIn("'--no-cache'",helper);self.assertNotIn("'--push'",helper)


if __name__=='__main__': unittest.main()
