"""Collect explicitly supplied completed Actions runs. Never dispatch workflows."""
import argparse
import csv
from datetime import datetime
import json
from pathlib import Path
import statistics
import subprocess

REPO = 'nineOuting/nineouting-user-service'
SHA = '7eb77cd22632de192bfb01de3d5bcf2c80f770b5'
METRICS = ['gradle_seconds', 'test_task_seconds', 'artifact_prepare_seconds',
           'artifact_upload_seconds', 'artifact_download_seconds', 'artifact_check_seconds',
           'docker_seconds', 'build_job_seconds', 'image_job_seconds', 'comparable_ci_seconds',
           'workflow_elapsed_seconds', 'first_job_queue_seconds', 'between_jobs_seconds', 'comparable_core_seconds']


def elapsed(start, end):
    if not start or not end: return None
    parse = lambda value: datetime.fromisoformat(value.replace('Z', '+00:00'))
    return (parse(end) - parse(start)).total_seconds()


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/' + path], text=True))


def summarize(rows):
    summaries = []
    for cache in sorted({r['cache'] for r in rows}):
        for method in ['original', 'reuse']:
            attempts = [r for r in rows if r['cache'] == cache and r['method'] == method]
            successes = [r for r in attempts if r['success']]
            block = {'cache': cache, 'method': method, 'attempts': len(attempts),
                     'successes': len(successes),
                     'success_rate': len(successes) / len(attempts) if attempts else None, 'metrics': {}}
            for metric in METRICS:
                values = [r[metric] for r in successes if r.get(metric) is not None]
                block['metrics'][metric] = {'n': len(values), 'mean': statistics.mean(values) if values else None,
                    'median': statistics.median(values) if values else None,
                    'min': min(values) if values else None, 'max': max(values) if values else None,
                    'sample_stddev': statistics.stdev(values) if len(values) > 1 else None}
            summaries.append(block)
    improvements = []
    for cache in sorted({r['cache'] for r in rows}):
        old = next(s for s in summaries if s['cache'] == cache and s['method'] == 'original')
        new = next(s for s in summaries if s['cache'] == cache and s['method'] == 'reuse')
        for metric in ['docker_seconds', 'image_job_seconds', 'comparable_ci_seconds', 'comparable_core_seconds']:
            for statistic in ['mean', 'median']:
                a = old['metrics'][metric][statistic]; b = new['metrics'][metric][statistic]
                improvements.append({'cache': cache, 'metric': metric, 'statistic': statistic,
                    'saved_seconds': a - b if a is not None and b is not None else None,
                    'reduction_percent': (a - b) / a * 100 if a and b is not None else None})
    return {'groups': summaries, 'improvements': improvements,
            'note': 'Successful comparable samples only; failures retained in CSV. Test time is included in Gradle time. No ECR/GHCR publishing or deployment measured. Warm cache effect is not measured in this cold campaign.'}


def collect_one(entry, root):
    number = int(entry['run_id']); attempt = int(entry.get('attempt', 1))
    run = api(f'actions/runs/{number}/attempts/{attempt}')
    pages = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp',
        f'repos/{REPO}/actions/runs/{number}/attempts/{attempt}/jobs?per_page=100'], text=True))
    jobs = [j for page in pages for j in page['jobs']]
    folder = root / f'run-{number}-attempt-{attempt}'; folder.mkdir(parents=True, exist_ok=True)
    (folder / 'run.json').write_text(json.dumps(run, indent=2))
    (folder / 'jobs.json').write_text(json.dumps(jobs, indent=2))
    row = {k: None for k in METRICS}
    row.update(experiment=entry['experiment'], method=entry['method'], trial=entry['trial'],
        phase=entry['phase'], cache='cold', source_sha=None, expected_source_sha=SHA, control_sha=run['head_sha'],
        run_id=number, attempt=attempt, url=run['html_url'], conclusion=run.get('conclusion'),
        success=False, error='', campaign=entry['campaign'], runner_fingerprint='', platforms='linux/amd64,linux/arm64')
    if run['event'] != 'workflow_dispatch' or run['path'] != '.github/workflows/scrum-287-benchmark.yml':
        raise ValueError('Not the isolated benchmark workflow')
    if run['status'] != 'completed': raise ValueError(f'Run {number} is not complete')
    measured = folder / 'measurements'
    downloaded = False
    for prefix in ['image', 'build']:
        result = subprocess.run(['gh', 'run', 'download', str(number), '--repo', REPO,
            '--name', f'{prefix}-measurements-{number}-{attempt}', '--dir', str(measured)], capture_output=True, text=True)
        if result.returncode == 0: downloaded = True; break
    read = lambda name: json.loads((measured / name).read_text())
    if downloaded and (measured / 'identity.json').exists() and (measured / 'conditions.json').exists():
        conditions = read('conditions.json')
        expected = json.loads((Path(__file__).parent / 'conditions.json').read_text())
        if conditions != expected: raise ValueError('Benchmark lock differs from reviewed conditions')
        identity = read('identity.json')
        for field in ['method', 'trial', 'phase', 'campaign']:
            if identity[field] != row[field]: raise ValueError('Manifest identity mismatch: ' + field)
        if identity['source_sha'] != SHA or identity['cache'] != 'cold': raise ValueError('Wrong source/cache')
        row['source_sha'] = identity['source_sha']
        hardware = {name: (measured / f'{name}.txt').read_text() for name in ['cpu', 'memory']}
        image_runner = read('image-runner.json') if (measured / 'image-runner.json').exists() else {}
        row['runner_fingerprint'] = json.dumps({'build': {k: identity.get(k) for k in ['runner_os','runner_arch','runner_image','runner_image_version']}, 'build_hardware': hardware, 'image': image_runner}, sort_keys=True)
        for field, name in [('gradle_seconds', 'gradle-time.json'), ('test_task_seconds', 'test-time.json'),
            ('docker_seconds', 'docker-time.json'), ('artifact_prepare_seconds', 'artifact-prepare-time.json'),
            ('artifact_check_seconds', 'artifact-check-time.json')]:
            if (measured / name).exists(): row[field] = read(name)['seconds']
        if entry['method'] == 'original':
            for key in ['artifact_prepare_seconds','artifact_upload_seconds','artifact_download_seconds','artifact_check_seconds']: row[key] = 0.0
        errors = [json.loads(p.read_text()) for p in measured.glob('error-*.json')]
        row['error'] = json.dumps(errors) if errors else ''
    else:
        failures = [json.loads(p.read_text()) for p in measured.glob('error-*.json')] if downloaded else []
        row['error'] = 'Measurement identity/artifact unavailable; ' + json.dumps(failures) + '; inspect saved run/jobs JSON and GitHub logs'
    named = {j['name']: j for j in jobs}
    for name in ['build', 'image']:
        job = named.get(name, {})
        row[name + '_job_seconds'] = elapsed(job.get('started_at'), job.get('completed_at'))
        for step in job.get('steps', []):
            metric = {'Upload tested application':'artifact_upload_seconds',
                      'Download tested application':'artifact_download_seconds'}.get(step['name'])
            if metric and step.get('conclusion') == 'success':
                row[metric] = elapsed(step.get('started_at'), step.get('completed_at'))
        if job.get('conclusion') not in ('success', None):
            row['error'] += f' {name}: {job.get("conclusion")}; ' + ', '.join(s['name'] for s in job.get('steps', []) if s.get('conclusion') == 'failure')
    build = named.get('build', {}); image = named.get('image', {})
    if row['build_job_seconds'] is not None and row['image_job_seconds'] is not None:
        row['comparable_ci_seconds'] = row['build_job_seconds'] + row['image_job_seconds']
    row['first_job_queue_seconds'] = elapsed(run['created_at'], build.get('started_at'))
    row['between_jobs_seconds'] = elapsed(build.get('completed_at'), image.get('started_at'))
    row['workflow_elapsed_seconds'] = elapsed(run['created_at'], max((j['completed_at'] for j in jobs if j.get('completed_at')), default=None))
    verification = read('image-verification.json') if downloaded and (measured / 'image-verification.json').exists() else {}
    core = ['gradle_seconds','artifact_prepare_seconds','artifact_upload_seconds','artifact_download_seconds','artifact_check_seconds','docker_seconds']
    row['comparable_core_seconds'] = sum(row[k] for k in core) if all(row[k] is not None for k in core) else None
    row['success'] = (run['conclusion'] == 'success' and verification.get('success') is True
                      and all(row[m] is not None for m in METRICS[:11]) and row['comparable_core_seconds'] is not None and not row['error'])
    if not row['success'] and not row['error']: row['error'] = 'Incomplete metrics or image verification'
    return row


def report(path, rows, summary):
    lines = ['# SCRUM-287 CI 반복 측정 결과', '',
        '대상: 프로그램 제작·기존 테스트·파일 전달·두 플랫폼 포장. 실제 배포와 이미지 게시 제외.',
        '전체 Job 합산에는 측정·결과 보관·이미지 검증 오버헤드도 포함됩니다. 핵심 단계 합산은 Gradle 전체 + 필요한 파일 전달 + Docker 포장입니다. 테스트 시간은 Gradle 전체에 이미 포함됩니다.', '',
        '| 캐시 | 방식 | 항목 | 성공 표본 | 평균(초) | 중앙값(초) | 최소(초) | 최대(초) | 표본 표준편차(초) |',
        '|---|---|---|---:|---:|---:|---:|---:|---:|']
    fmt = lambda value: '미측정' if value is None else f'{value:.3f}'
    for group in summary['groups']:
        for metric in ['gradle_seconds','test_task_seconds','docker_seconds','image_job_seconds','comparable_ci_seconds','comparable_core_seconds']:
            v=group['metrics'][metric]
            lines.append(f"| {group['cache']} | {group['method']} | {metric} | {v['n']} | " + ' | '.join(fmt(v[k]) for k in ['mean','median','min','max','sample_stddev']) + ' |')
    lines += ['', '| 캐시 | 기준 | 통계 | 절감(초) | 감소율(%) |', '|---|---|---|---:|---:|']
    for v in summary['improvements']:
        lines.append(f"| {v['cache']} | {v['metric']} | {v['statistic']} | {fmt(v['saved_seconds'])} | {fmt(v['reduction_percent'])} |")
    lines += ['', '## 성공률·실패 기록']
    for group in summary['groups']:
        lines.append(f"- {group['cache']} / {group['method']}: {group['successes']}/{group['attempts']} 성공")
    for row in rows:
        if not row['success']: lines.append(f"- 실패 {row['experiment']}: {row['error']} — {row['url']}")
    lines += ['', '## 확인 상태', f"- 1차 통과: {summary.get('pilot_gate_passed',False)}", f"- 20회 완료 조건 충족: {summary['completed_expected_20']}",
        f"- 실행 환경 추가 검토 필요: {summary['environment_review_required']}", '', '## 한계',
        '- 캐시 없는 조건만 측정했습니다. Warm 실행과의 차이는 미측정이며 기존 1회 관측값과 평균을 섞지 않았습니다.',
        '- 실패한 실행은 삭제하지 않고 성공률에 포함했습니다. 시간 통계는 검증에 성공한 표본만 사용하며 표본 수를 표시합니다.',
        '- 각 방식 최대 10개 표본으로 장기 고정 개선율을 보장하지 않습니다. Runner 물리 장비와 외부 다운로드 지연이 다를 수 있습니다.',
        '- 원본 CSV의 대기 시간·실행 환경과 raw/의 GitHub 원본 기록을 함께 확인해야 합니다. p95는 계산하지 않았습니다.', '', '## 실행 원본']
    lines.extend(f"- {r['experiment']} / {r['method']}: {r['url']} (attempt {r['attempt']})" for r in rows)
    path.write_text('\n'.join(lines)+'\n')


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True); a = parser.parse_args()
    entries = json.loads(a.manifest.read_text()); rows = []; seen = set()
    if not entries: raise ValueError('No real runs supplied; refusing to fabricate results')
    for entry in entries:
        identity = (entry['campaign'], entry['method'], entry['trial'])
        if identity in seen: raise ValueError('Duplicate trial; record retries separately')
        seen.add(identity); rows.append(collect_one(entry, a.output / 'raw'))
    a.output.mkdir(parents=True, exist_ok=True)
    with (a.output / 'measurements.csv').open('w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    summary = summarize(rows)
    variations = sorted({(json.loads(r['runner_fingerprint']).get('build', {}).get('runner_image_version'), json.loads(r['runner_fingerprint']).get('image', {}).get('runner_image_version')) for r in rows if r.get('runner_fingerprint')}, key=str)
    summary['runner_image_version_pairs'] = variations
    summary['environment_review_required'] = len(variations) > 1 or any(a != b for a,b in variations)
    summary['completed_expected_20'] = (len(rows)==20 and not summary['environment_review_required'] and len({r['campaign'] for r in rows}) == 1 and all(r['success'] for r in rows)
        and {r['trial'] for r in rows if r['method']=='original'}==set(range(1,11))
        and {r['trial'] for r in rows if r['method']=='reuse'}==set(range(1,11)))
    pilot = [r for r in rows if r['phase']=='pilot']
    summary['pilot_gate_passed'] = (len(pilot)==10 and all(r['success'] for r in pilot) and len({r['campaign'] for r in pilot})==1 and not summary['environment_review_required'] and all({r['trial'] for r in pilot if r['method']==m}==set(range(1,6)) for m in ['original','reuse']))
    (a.output / 'statistics.json').write_text(json.dumps(summary, indent=2)+'\n')
    report(a.output / 'report.md', rows, summary)
    print('Collected', len(rows), 'actual runs; see measurements.csv and statistics.json')


if __name__ == '__main__': main()
