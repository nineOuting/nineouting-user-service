"""Read an OCI archive without loading or running images. No network operations."""
import argparse
import hashlib
import io
import json
import tarfile
import zipfile
from pathlib import Path


def verify(archive, reference):
    reference = json.loads(reference.read_text())
    results = []
    with tarfile.open(archive, 'r:*') as outer:
        def read(name):
            file = outer.extractfile(name)
            if file is None: raise ValueError('Missing OCI member: ' + name)
            return file.read()
        def blob(descriptor):
            algorithm, digest = descriptor['digest'].split(':', 1)
            if algorithm != 'sha256': raise ValueError('Unsupported digest')
            data = read('blobs/sha256/' + digest)
            if hashlib.sha256(data).hexdigest() != digest: raise ValueError('OCI blob checksum mismatch')
            return data
        def visit(descriptor):
            manifest = json.loads(blob(descriptor))
            if 'manifests' in manifest:
                for child in manifest['manifests']: visit(child)
                return
            config = json.loads(blob(manifest['config']))
            arch = config.get('architecture')
            if arch not in ('amd64', 'arm64') or config.get('os') != 'linux':
                raise ValueError('Unexpected image platform')
            runtime = config['config']
            assert runtime['User'] == 'app'
            assert runtime['WorkingDir'] == '/app'
            assert runtime['Entrypoint'] == ['java', '-jar', 'app.jar']
            assert '8081/tcp' in runtime['ExposedPorts']
            assert runtime['Healthcheck']['Test'] == ['CMD-SHELL', 'wget -q --spider http://localhost:8081/actuator/health || exit 1']
            assert runtime['Healthcheck']['Interval'] == 10_000_000_000
            assert runtime['Healthcheck']['Timeout'] == 5_000_000_000
            assert runtime['Healthcheck']['StartPeriod'] == 30_000_000_000
            assert runtime['Healthcheck']['Retries'] == 10
            jar = None
            for layer in manifest['layers']:
                with tarfile.open(fileobj=io.BytesIO(blob(layer)), mode='r:*') as contents:
                    for member in contents:
                        name = member.name.lstrip('./')
                        if name == 'app/.wh.app.jar': jar = None
                        elif name == 'app/app.jar':
                            stream = contents.extractfile(member)
                            if stream is None: raise ValueError('JAR is not a regular file')
                            jar = stream.read()
            if jar is None: raise ValueError('Image has no application JAR')
            with zipfile.ZipFile(io.BytesIO(jar)) as zipped:
                names = [m.filename for m in zipped.infolist() if not m.is_dir()]
                if len(names) != len(set(names)): raise ValueError('Duplicate JAR entry')
                hashes = {n: hashlib.sha256(zipped.read(n)).hexdigest() for n in sorted(names)}
            if hashes != reference['entries']: raise ValueError('Packaged JAR contents differ from tested application')
            results.append({'platform': 'linux/' + arch, 'contents_match': True,
                            'exact_jar_sha_match': hashlib.sha256(jar).hexdigest() == reference['sha256'],
                            'runtime_configuration_match': True})
        index = json.loads(read('index.json'))
        for descriptor in index['manifests']: visit(descriptor)
    if sorted(r['platform'] for r in results) != ['linux/amd64', 'linux/arm64']:
        raise ValueError('Expected exactly two platform images')
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--reference', type=Path, required=True); parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.archive, args.reference)
        args.output.write_text(json.dumps({'success': True, 'images': result}, indent=2) + '\n')
        print('PASS: both image JAR contents and runtime settings match the tested application')
    except Exception as error:
        args.output.write_text(json.dumps({'success': False, 'error': str(error)}, indent=2) + '\n')
        raise
