# nineouting-user-service

회원가입·이메일 인증·로그인·JWT 발급·프로필을 맡는 서비스.

- 설계(데이터·API·에러 코드): [nineouting-docs/docs/services/user-service.md](https://github.com/nineOuting/nineouting-docs/blob/main/docs/services/user-service.md)
- 요구사항: REQ-004~014, REQ-102~105
- 포트: `8081`

## 로컬 실행

필요한 것: JDK 21, Docker

1. GitHub Packages 토큰 설정 (공통 모듈을 받기 위해, 처음 한 번)
   `~/.gradle/gradle.properties`:
   ```properties
   gpr.user=<GitHub 아이디>
   gpr.key=<read:packages 권한 토큰>
   ```
2. 의존 서비스 실행
   ```bash
   docker compose up -d
   ```
   | 서비스 | 주소 | 비고 |
   |--------|------|------|
   | MySQL | `localhost:3307` | DB `user_db` / `user_service` / `user_service` |
   | Redis | `localhost:6379` | |
   | Kafka | `localhost:9092` | |
   | S3 (LocalStack) | `http://localhost:4566` | 버킷 `nineouting-local` |
3. 서비스 실행
   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=local'
   ```
4. 확인: `http://localhost:8081/actuator/health`

## 테스트

```bash
./gradlew test   # Testcontainers로 MySQL을 띄우므로 Docker가 켜져 있어야 한다
```

## 환경 변수

| 이름 | 기본값 | 설명 |
|------|--------|------|
| `SERVER_PORT` | `8081` | |
| `DB_URL` | `jdbc:mysql://localhost:3307/user_db` | |
| `DB_USERNAME` / `DB_PASSWORD` | `user_service` | |
| `SPRING_PROFILES_ACTIVE` | — | `local`: 사람이 읽는 로그, 추적 전송 끔 |

## Docker 이미지

```bash
docker build --secret id=gpr_actor,env=GITHUB_ACTOR --secret id=gpr_token,env=GPR_TOKEN -t user-service .
```
