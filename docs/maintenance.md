# Spotless Daemon 유지보수

## 소유권과 호환성

도구 버전은 `gradle.properties`에서 관리합니다. 소비 프로젝트는 Gradle 플러그인 버전을 선택하고,
별도로 Spotless·포매터 버전과 규칙을 소유합니다. IDE ZIP에는 연결 코드만 들어갑니다.

`SpotlessDaemonPlugin`은 루트 프로젝트에 시작·적용·상태·종료 태스크를 등록합니다.
`export.gradle`은 그 프로젝트가 적용한 Spotless `spotlessJava` 태스크의 `stepsInternalRoundtrip`,
인코딩, 줄바꿈 정책, 대상 파일을 읽습니다. 설정과 JAR 경로를 내보낸 뒤 daemon에 전달합니다.
`Worker`는 실제 프로젝트 라이브러리의 `Formatter`·`DirtyState`를 복원·호출합니다.

Spotless 내부 API 변경이나 직렬화 불가 단계는 실패할 수 있습니다. 임의 버전·이전 설정으로 대체하지 않습니다.
`ratchetFrom`은 지원하지 않습니다. Gradle configuration cache와 isolated projects도 지원하지 않습니다.

## 프로세스와 통신

- canonical Gradle root당 `SharedDaemon` 하나와 그 소유의 worker 하나입니다.
- `.gradle/spotless-daemon/start.lock`은 시작·설정 갱신을 직렬화합니다.
  `daemon.lock`은 프로세스 수명 동안 유지되어 중복 서버를 막습니다.
- daemon은 루프백 TCP의 임의 포트에 바인딩하고 임의 토큰을 검사합니다.
  endpoint 파일은 임시 파일 작성 후 atomic move로 교체합니다.
- 프로토콜 2는 magic, token, action, snapshot key, canonical path, UTF-8 payload를 사용합니다.
  snapshot 프로토콜은 기존 1을 유지합니다. 실패 응답은 원문에 쓰지 않습니다.
- snapshot key는 포맷 설정·실제 대상 목록·설정 fingerprint·직렬화 bytes·classpath로 만듭니다.
  같으면 worker를 재사용하고 다르면 교체합니다. 엔진 JAR SHA-256이나 실행 JDK가 달라지면 daemon을 교체합니다.
- stale PID를 강제로 죽이지 않습니다. 인증된 연결로 종료를 요청하고 수명 잠금으로 시작을 제어합니다.
- 클라이언트 위치는 `client-runtime.txt`에 atomic move로 기록합니다. hook은 이 엔진의 클라이언트를 직접 실행합니다.
- IDE 종료는 연결만 닫습니다. 명시적 stop 또는 기본 30분 idle 종료가 worker도 정리합니다.
- daemon은 Gradle을 실행하지 않습니다. IDE는 필요한 경우 Gradle start 태스크를 실행하고,
  hook apply 태스크는 실행 중인 Gradle 안에서 직접 설정을 내보냅니다.
- 시작된 프로세스의 파일·네트워크 접근은 샌드박스화하지 않습니다. 신뢰하는 프로젝트 빌드에서 사용합니다.

## 회귀 검증

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew clean build pluginZip
```

`check`는 `build/repository`에 marker와 구현 JAR를 게시하고, 임시 소비 프로젝트에서 실제로 해석합니다.
따라서 source classpath만 연결한 테스트와 달리 플러그인 패키징·배포 메타데이터도 경로에 포함됩니다.
전역 Maven 로컬 저장소는 변경하지 않습니다.

- 기존 `FormatterSelfTest`: Groovy/Kotlin DSL, Spotless 8.10.2/8.0.0, Palantir/Google/Eclipse,
  Gradle 결과 비교, 신규·제외 파일, 없는 파일 요청 후 생성, 설정 변경·실패·복구, 다중 모듈.
- `ProcessSelfTest`: 문법 오류, worker 재사용·강제 종료 후 복구, timeout, 종료.
- `SharedDaemonSelfTest`: 편집기 연결 종료 후 실제 `scripts/ai-hook.py` 실행,
  반복 hook의 Gradle 호출 0회, 시작·설정 변경 시 1회, 공용 PID/worker, 80개 동시 요청, hook만의 시작, stale endpoint 복구, 잘못된 토큰 거부,
  UTF-16LE·CRLF Gradle byte 일치, 외부 includeBuild를 포함한 설정 갱신, 동시 시작, stop과 idle 종료.

CI는 SDK 없는 `check engineDist`를 실행합니다. 실제 IntelliJ UI 저장 동작·Plugin Verifier·
Marketplace 승인과 별개입니다. SDK는 `pluginZip`에만 필요합니다.

## 배포 준비

`com.gradle.plugin-publish`로 구현 JAR, sources/javadoc, plugin marker와 Portal 메타데이터를 생성합니다.
`./gradlew publishAllPublicationsToTestRepository`는 로컬 파일 저장소에만 게시합니다. `-PspotlessDaemonRepository=/path/to/project/.gradle/spotless-daemon-repository`로 게시 위치를 지정할 수 있습니다.

공개 게시 전 버전, GitHub 저장소 주소, 라이선스, 호환성 검증과 Plugin Portal 계정 소유권을 확인하세요.
게시 자격 증명은 Git에 넣지 않습니다. API 키가 준비된 뒤 `publishPlugins --validate-only`로 확인하고,
승인된 출시 작업에서 `publishPlugins`를 실행합니다. 두 옵션의 차이는
[Gradle 공식 배포 문서](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html)를 참고하세요.
이 저장소의 일반 build/check는 공개 게시를 수행하지 않습니다.

IDE ZIP의 Marketplace 업로드는 별도 절차입니다. 실제 설치·설정 변경은 사용자에게 맡깁니다.
