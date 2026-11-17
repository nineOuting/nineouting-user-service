# SCRUM-287 중복 빌드 제거 효과 반복 측정 — 승인 전 로컬 준비

관련 구현은 SCRUM-275 / 사용자 서비스 PR #40이며, 이번 반복 측정의 티켓은 SCRUM-287입니다.

현재는 측정 파일만 작성했습니다. 이 문서는 원격 게시·실행 승인이 아닙니다. 커밋·푸시·PR·병합·Actions 실행은 별도 승인이 필요합니다.

## 무엇을 비교하나

소스는 양쪽 모두 `7eb77cd22632de192bfb01de3d5bcf2c80f770b5`입니다. 이 커밋은 기존 Dockerfile을 그대로 보존하며, 개선용 Dockerfile.ci와 CI 전달 단계만 추가합니다. Java 21.0.13+9, Gradle 9.5.1, 동일 Runner 종류와 도구 버전, amd64/arm64를 사용합니다. 고정 조건과 이미지 digest는 `scripts/benchmark/conditions.json`에 있습니다.

각 실행은 독립된 build/image 두 Job입니다. build는 기존 `gradlew build`로 제작과 테스트를 수행합니다. Gradle profile 수집과 빌드 캐시 비활성화 옵션만 양쪽에 동일하게 추가합니다. 기존 방식 image는 소스로 bootJar를 다시 만들고, 개선 방식 image는 해당 실행에서 검사한 JAR을 전달받습니다. 별도 Dockerfile 복사본에서 기반 이미지만 고정 digest로 치환하며 기존 파일은 수정하지 않습니다.

이미지는 OCI/gzip 형식으로 Runner 내부에만 출력합니다. OCI/레이어 checksum 및 두 플랫폼의 JAR 각 항목 내용, 실행 사용자·명령·포트·상태 확인 설정을 검증합니다. JAR ZIP 메타데이터 차이와 실제 파일 내용 차이를 구분하며, 내용이 다르면 실패입니다. OCI export와 검증은 실제 레지스트리 게시 비용과 같지 않습니다.

## 안전 범위

- workflow_dispatch 이외 트리거 없음. push/PR/schedule 트리거 없음.
- 기존 ci.yml/Dockerfile/Dockerfile.ci/애플리케이션 변경 없음.
- contents: read만 부여. AWS/OIDC, 레지스트리 로그인, 이미지 Push, infra PR, Argo Sync, Kubernetes 작업 없음.
- 기존 GPR_TOKEN으로 공통 부품을 읽는 권한만 사용. 비밀값을 측정 파일에 기록하지 않음.
- Docker cache import/export, Gradle cache restore/save 없음. QEMU 이미지·Buildx 바이너리의 Actions 캐시도 비활성화.
- Dockerfile 기반 이미지와 도구 버전 고정. 애플리케이션 빌드 캐시는 Cold지만 외부 다운로드 서버·Runner 사전 설치 도구까지 캐시가 없다는 뜻은 아님.
- OCI는 검증 후 삭제. 이미지/JAR 원본을 결과 Artifact에 넣지 않음. 개선 방식의 전달용 JAR만 Artifact 보관 1일, 작은 측정 자료는 7일.
- 기존 정상 배포 워크플로를 재실행하지 않음.

## 등록 전 확인할 기본 브랜치

저장소 기본 브랜치는 `main`입니다. 수동 워크플로를 등록하려면 승인 후 **main 기준 별도 등록 브랜치에 이 측정용 신규 파일만 옮겨** 검토해야 합니다. 현재 로컬 미리보기 브랜치는 dev 기준이므로 이를 main에 그대로 PR하거나 dev 전체를 main으로 병합하면 안 됩니다.

새 측정 파일만 main에 등록하고, 워크플로가 읽을 애플리케이션은 위 고정 SHA를 사용합니다. main 등록 PR 자체로 기존 CI의 PR 검사가 실행될 수 있지만, dev Push가 아니므로 정상 DEV 배포 Job은 실행 대상이 아닙니다. 등록·실행은 승인 이후 진행합니다.

## 20회 실행 절차

1. 신규 파일 diff와 권한·비용을 검토하고 게시/등록/실행 승인을 받습니다.
2. 한 캠페인 이름을 정합니다. 예: `scrum287-cold-20261117`.
3. 아래 순서로 trial 1..5의 10개 실행을 **하나씩 실행하고 완료를 기다립니다**.
4. 실제 Run ID/attempt를 로컬 runs.json에 기록하고 수집기를 실행합니다.
5. 10개 성공, 테스트 통과, JAR 내용 일치, 자료 누락 없음, Runner 이미지 버전 일치 및 이상 지연 여부를 확인합니다. 문제 발생 시 중단하고 보고합니다.
6. 문제가 없으면 같은 조건·소스로 trial 6..10의 10개를 추가합니다.
7. 실패는 원본에 남깁니다. 실패를 숨기거나 자동으로 다른 표본으로 교체하지 않습니다. 재시도는 별도 승인·기록합니다.

| trial | phase | 실행 순서 |
|---|---|---|
| 1 | pilot | original → reuse |
| 2 | pilot | reuse → original |
| 3 | pilot | original → reuse |
| 4 | pilot | reuse → original |
| 5 | pilot | original → reuse |
| 6 | confirm | reuse → original |
| 7 | confirm | original → reuse |
| 8 | confirm | reuse → original |
| 9 | confirm | original → reuse |
| 10 | confirm | reuse → original |

동시 실행을 막는 전용 concurrency 그룹이 있습니다. GitHub concurrency는 20개의 완전한 FIFO 대기열이 아니므로 20회를 한꺼번에 dispatch하지 않습니다. Job 제한은 build 20분/image 45분입니다. 타임아웃도 실패 기록에 포함합니다.

승인 후 한 실행 예시(지금 실행하지 않음):

```bash
gh workflow run scrum-287-benchmark.yml --repo nineOuting/nineouting-user-service --ref main \
  -f campaign=scrum287-cold-20261117 -f method=original -f trial=1 -f phase=pilot
```

## 수집·통계

runs.json은 실제 Run ID를 확보한 뒤 작성하는 배열입니다. 각 행은 experiment(1..20), run_id, attempt, campaign, trial(1..10), phase(pilot/confirm), method(original/reuse) 필드를 갖습니다. 존재하지 않는 Run ID나 임의 측정값을 예시 데이터로 넣지 않습니다.

```bash
python scripts/benchmark/collect.py --manifest /실제경로/runs.json --output /실제경로/results
```

수집기는 워크플로 실행을 생성하지 않습니다. 지정한 실행의 Job/Step 원본 JSON과 측정 Artifact만 내려받습니다. 대상 워크플로·수동 실행 여부·소스 SHA·조건 파일·실험 식별자를 검증합니다. 실제 기록이 없으면 수집하지 않습니다. Job 실패·파일 불일치·자료 누락은 성공으로 처리하지 않습니다.

결과:
- measurements.csv: 20개 실제 실행별 항목 및 실패 이유.
- raw/: GitHub Run/Job 원본, Gradle/Docker 로그·profile·파일 검증 결과.
- statistics.json / report.md: 평균·중앙값·최소·최대·표본 표준편차, 감소량/감소율, 성공률, 한계.

측정 기준:
- gradle_seconds: 전체 제작·테스트 명령의 경과 시간. test_task_seconds는 그 안에 포함됨(중복 합산 금지).
- artifact_*: 준비·업로드·다운로드·검증. 기존 방식의 불필요한 JAR 전달은 실행하지 않아 0으로 표시.
- docker_seconds: 두 플랫폼 제작부터 OCI 출력 완료까지.
- comparable_core_seconds: Gradle 전체 + 필요한 Artifact 전달 + Docker. 검증된 핵심 단계 합계.
- build/image_job_seconds와 comparable_ci_seconds: 두 Job 전체 및 합계. 환경 준비·측정 자료 보관·이미지 검증 오버헤드 포함.
- workflow_elapsed_seconds: 실행 생성부터 마지막 Job 종료. first_job_queue_seconds/between_jobs_seconds는 별도 기록.

두 Job은 새 Runner에서 실행됩니다. 같은 종류를 지정해도 물리 CPU·네트워크는 동일하다고 보장할 수 없어 두 Job의 하드웨어와 OS 이미지 버전을 기록합니다. 이미지 버전 변동은 추가 검토 대상으로 표시합니다. 환경 문제는 1차 확인에서 중단합니다.

현재 20회 설계는 모두 Cold입니다. Warm 차이는 미측정으로 표시하며 Cold와 섞지 않습니다. Warm까지 같은 수준으로 결론내려면 별도 표본을 승인받아야 합니다. 기존 한 번의 전체 CI 14분30초/4분15초는 게시·GitOps 포함 범위가 달라 이 20회 평균과 합치지 않습니다.

## 비용과 한계

확인 시점 저장소는 public입니다. 일반 GitHub 제공 Runner 사용은 컴퓨팅 비용 무료 대상이나 동시 실행 자리를 차지해 팀 검사에 대기가 생길 수 있습니다. Artifact 저장은 별도 사용량이므로 위 보관 기간으로 제한합니다. 유료 대형 Runner는 사용하지 않습니다. 비용 정책은 실행 승인 시 다시 확인합니다.

예상 완료 시간은 이전 1회 관측을 바탕으로 3~5시간 정도이며 보장이 아닙니다. 외부 의존성 다운로드·ARM 실행 흉내 내기·Runner 혼잡·저장소 오류가 변동 요인입니다. 실제 배포 시간·Warm 캐시 개선율·장기 p95는 이 실험으로 확정하지 않습니다.

공식 참고: https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow , https://docs.github.com/en/billing/concepts/product-billing/github-actions

## 로컬 도구 검증 (실험 실행 아님)

```bash
python -m unittest discover -s scripts/benchmark -p 'test_*.py' -v
actionlint .github/workflows/scrum-287-benchmark.yml
```

테스트의 작은 가짜 JAR/OCI/시간 문자열은 도구 검사용 fixture이며 실제 성능 자료나 CSV로 보고하지 않습니다.
