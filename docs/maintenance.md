# Spotless Bridge 유지보수

## 소유권과 호환성

도구 버전은 `gradle.properties`에서 관리하며 `selfTest` 인자로 전달해 Groovy/Kotlin 소비 프로젝트에서도 같은 버전을 사용합니다. 소비 프로젝트는 Gradle 플러그인 버전을 선택하고,
별도로 Spotless·포매터 버전과 규칙을 소유합니다. IDE ZIP에는 연결 코드만 들어갑니다.

`SpotlessBridgePlugin`은 루트 프로젝트에 시작·적용·상태·종료 태스크를 등록합니다.
`export.gradle`은 그 프로젝트가 적용한 Spotless `spotlessJava` 태스크의 `stepsInternalRoundtrip`,
인코딩, 줄바꿈 정책, 대상 파일을 읽습니다. 설정과 JAR 경로를 내보낸 뒤 daemon에 전달합니다.
`Worker`는 실제 프로젝트 라이브러리의 `Formatter`·`DirtyState`를 복원·호출합니다.

최소 실행 JDK는 17입니다. Java 17 대상 바이트코드와 Gradle Module Metadata의 JVM 버전 속성으로 선언하며,
낮은 JDK에서는 Gradle이 플러그인을 로드하기 전에 현재·최소 버전과 JDK 변경 안내를 출력합니다.
따라서 플러그인 클래스 안에 도달할 수 없는 JDK 검사를 따로 두지 않습니다.

Spotless 내부 API 변경이나 직렬화 불가 단계는 실패할 수 있습니다. 임의 버전·이전 설정으로 대체하지 않습니다.
`ratchetFrom`은 지원하지 않습니다. Gradle configuration cache와 isolated projects도 지원하지 않으며 plugin compatibility 메타데이터에 둘 다 `false`로 선언합니다.

## 프로세스와 통신

- canonical Gradle root당 `SharedDaemon` 하나와 그 소유의 worker 하나입니다.
- `.gradle/spotless-bridge/start.lock`은 시작·설정 갱신을 직렬화합니다.
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

JDK 21을 유지하고 소비 프로젝트 Gradle 버전만 바꾸는 호환성 검사는 다음과 같습니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist -PtestGradleVersion=9.8.1
```

`testGradleVersion`의 기본값은 `8.14.3`입니다. CI는 JDK 17·21에서 각각 `8.14.3`, `8.14.6`, `9.0.0`, `9.8.1`을,
JDK 25에서 `9.8.1`을 검사하도록 구성합니다. 총 아홉 조합입니다.
Bridge 자체 빌드는 저장소의 8.14.3 wrapper를 유지하고, Groovy/Kotlin 임시 소비 프로젝트에만 지정한
배포본 URL을 설정합니다. 각 소비 프로젝트는 `--version` 결과를 확인한 뒤 회귀 검사를 실행합니다.
Spotless 8.10.2로 전체 검사를 실행하고, 같은 Gradle에서 8.0.0 전환·복원 시 결과 일치와 worker 교체도 검사합니다.
8.0.0 전체 기능 조합이나 모든 Gradle 8.x/9.x를 검증한 것은 아닙니다.

빌드 JDK는 21로 유지하고 엔진·플러그인·테스트를 `--release 17`로 컴파일합니다.
IntelliJ adapter만 IDE SDK에 맞춰 `--release 21`을 유지합니다.

JDK 17 검사는 다음과 같습니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist \
  -PtestGradleVersion=8.14.3 -PtestJavaHome=/path/to/jdk17
```

JDK 25는 Gradle 9.8.1과 함께 검사합니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist \
  -PtestGradleVersion=9.8.1 -PtestJavaHome=/path/to/jdk25
```

`testJavaHome`을 지정하면 `selfTest` JavaExec 실행 파일과 자식 프로세스의 `JAVA_HOME`을 함께 선택합니다.
기본값은 빌드 JVM의 홈 경로입니다. 테스트 프로세스, 소비 Gradle, daemon, worker와 hook이 같은 JDK를 사용하며,
테스트는 환경 변수·실행 JVM 일치, daemon endpoint의 JDK, hook runtime의 JDK를 검사합니다.
CI는 소비 JDK를 준비한 뒤 빌드 JDK를 21로 설정하고, 소비 JDK 경로를 `testJavaHome`으로 명시합니다.
아홉 조합을 로컬 macOS의 Zulu 17.0.20.1, Corretto 21.0.6, Temurin 25.0.4.1로 검증합니다.
엔진 class major version은 61이며, Gradle Module Metadata의 JVM 버전은 17입니다.
같은 엔진 JAR를 모든 소비 JDK에서 사용합니다. 이번 변경의 GitHub CI 실행은 push 후 별도 확인해야 합니다.
임의의 상위 JDK나 Java 25의 모든 문법 지원을 의미하지 않습니다.

Google Java Format은 JDK 17 테스트에서 1.28.0, JDK 21·25에서 1.30.0을 사용합니다.
JDK 17에서 1.30.0을 지정하면 실패하며 파일을 보존하는지, 1.28.0으로 복구하면 정상 포맷되는지도 검사합니다.
이 분기는 테스트 fixture에만 적용합니다. 운영 엔진은 프로젝트가 선택한 포매터 버전을 바꾸지 않습니다.
기존 공개 `v0.1.0` 태그·Release는 Java 21 대상으로 남겨 두며 이 변경으로 교체하지 않습니다.


`check`는 `build/repository`에 marker와 구현 JAR를 게시하고, 임시 소비 프로젝트에서 실제로 해석합니다.
따라서 source classpath만 연결한 테스트와 달리 플러그인 패키징·배포 메타데이터도 경로에 포함됩니다.
전역 Maven 로컬 저장소는 변경하지 않습니다.

- 기존 `FormatterSelfTest`: Groovy/Kotlin DSL, Spotless 8.10.2/8.0.0, Palantir/Google/Eclipse,
  Gradle 결과 비교, 신규·제외 파일, 없는 파일 요청 후 생성, 설정 변경·실패·복구, 다중 모듈.
- `ProcessSelfTest`: 문법 오류, worker 재사용·강제 종료 후 복구, timeout, 종료.
- `SharedDaemonSelfTest`: `spotlessBridgeInstallHook`으로 임시 소비 프로젝트에 hook 런처와 `LICENSE`를 설치하고, 런처 변경·0.1.0 스크립트 경고와 이전 호출 방식을 확인한 뒤 편집기 연결 종료 후 hook 실행,
  설정이 같은 등록 대상 파일의 반복 hook에서 Gradle 호출 0회, 시작·설정 변경 시 1회, 공용 PID/worker, 80개 동시 요청, hook만의 시작, stale endpoint 복구, 잘못된 토큰 거부,
  UTF-16LE·CRLF Gradle byte 일치, 외부 includeBuild를 포함한 설정 갱신, 동시 시작, stop과 idle 종료.

CI는 SDK 없는 `check engineDist`를 실행합니다. 실제 IntelliJ UI 저장 동작·Plugin Verifier·
Marketplace 승인과 별개입니다. SDK는 `pluginZip`에만 필요합니다.

## 배포 준비

`com.gradle.plugin-publish`로 구현 JAR, sources/javadoc, plugin marker와 Portal 메타데이터를 생성합니다.
`./gradlew publishAllPublicationsToTestRepository`는 로컬 파일 저장소에만 게시합니다. `-PspotlessBridgeRepository=/path/to/project/.gradle/spotless-bridge-repository`로 게시 위치를 지정할 수 있습니다.

라이선스는 MIT이며 `LICENSE`를 모든 JAR의 `META-INF`에 포함하고 Maven POM에도 기록합니다.
기본 사용 문서는 영문 `README.md`, 한국어 문서는 `README.ko.md`입니다. 동작이나 설치 방법이 바뀌면 둘 다 갱신합니다.

hook 런처(`src/main/resources/spotless-bridge/spotless-bridge`, `.cmd`)는 `LICENSE`와 함께 플러그인 JAR의
`spotless-bridge/`에 포함됩니다. 사용자는 `spotlessBridgeInstallHook`으로 이 파일들을 `tools/spotless-bridge/`
(또는 `-PspotlessBridgeHookDir`)에 생성합니다. 런처는 `client-runtime.txt`가 없을 때만 `gradlew spotlessBridgeStart`를
실행하고, 이후 `GradleFormatter --hook <root> <files...>`를 직접 실행합니다. 포맷 로직은 엔진에 있으므로 런처는
버전과 무관하게 유지하고, 바꿔야 할 때만 내용을 수정합니다. `spotlessBridgeStart`는 설치된 런처가 패키지된 런처와
다르거나(줄바꿈 차이는 무시) 0.1.0의 `ai-hook.py`가 남아 있으면 경고만 출력합니다. 0.1.0 스크립트가 쓰는
`<root> <list-file>` 호출 방식도 계속 지원합니다. Windows 런처는 CI에서 검증하지 않습니다.

공개 게시 전 버전, GitHub 저장소 주소, 호환성 검증과 Plugin Portal 계정 소유권을 확인하세요.
게시 자격 증명은 Git에 넣지 않습니다. API 키가 준비된 뒤 `publishPlugins --validate-only`로 확인하고,
승인된 출시 작업에서 `publishPlugins`를 실행합니다. 두 옵션의 차이는
[Gradle 공식 배포 문서](https://docs.gradle.org/current/userguide/publishing_gradle_plugins.html)를 참고하세요.
이 저장소의 일반 build/check는 공개 게시를 수행하지 않습니다.

IDE ZIP의 Marketplace 업로드는 별도 절차입니다. 실제 설치·설정 변경은 사용자에게 맡깁니다.
