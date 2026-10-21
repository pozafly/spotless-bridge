# Spotless Bridge 개발 안내

Spotless Bridge의 구조를 이해하고 코드를 수정·검증하는 기여자를 위한 문서입니다.
로컬 빌드와 설치 방법은 [소스 설치 안내](install-from-source.ko.md)를 참고하세요.

## 소유권과 호환성

Gradle 플러그인 버전은 `gradle.properties`, Rust CLI 버전은 `native/Cargo.toml`에서 같은 값으로 관리하며 `selfTest` 인자로 전달해 Groovy/Kotlin 소비 프로젝트에서도 같은 버전을 사용합니다. 소비 프로젝트는 Gradle 플러그인 버전을 선택하고,
별도로 Spotless·포매터 버전과 규칙을 소유합니다. IDE ZIP에는 연결 코드만 들어갑니다.

`SpotlessBridgePlugin`은 루트 프로젝트에 시작·적용·상태·종료 태스크를 등록합니다.
`export.gradle`은 그 프로젝트가 적용한 Spotless `spotlessJava` 태스크의 `stepsInternalRoundtrip`,
인코딩, 줄바꿈 정책, 대상 파일을 읽습니다. 설정과 JAR 경로를 내보낸 뒤 daemon에 전달합니다.
`Worker`는 실제 프로젝트 라이브러리의 `Formatter`·`DirtyState`를 복원·호출합니다.

최소 실행 JDK는 17입니다. Java 17 대상 바이트코드와 Gradle Module Metadata의 JVM 버전 속성으로 선언하며,
낮은 JDK에서는 Gradle이 플러그인을 로드하기 전에 현재·최소 버전과 JDK 변경 안내를 출력합니다.
따라서 플러그인 클래스 안에 도달할 수 없는 JDK 검사를 따로 두지 않습니다.

Gradle은 플러그인 적용 시 8.14.3 이상·10.0 미만인지 검사합니다. Spotless는 설정을 내보내기 전에 각 프로젝트에서
실제로 로드한 플러그인 JAR의 파일명으로 8.x인지 확인합니다. 공식 JAR에 Implementation-Version이 없어
Gradle이 생성한 `instrumented-` 접두사도 처리하며, 파일명에서 버전을 확인할 수 없는 사용자 제작 JAR은 중단합니다.
검증 목록 밖이지만 허용 범위 안인 버전은 경고만 표시합니다. 범위 밖 버전은 감지 버전·프로젝트·허용 범위와
조치 방법을 안내하고 기존 daemon 설정으로 대신 포맷하지 않습니다. IDE/hook 클라이언트도 버전 오류 내용을 전달합니다.

Spotless 내부 API 변경이나 직렬화 불가 단계는 허용 범위 안에서도 실패할 수 있습니다. 임의 버전·이전 설정으로 대체하지 않습니다.
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
- Java 엔진 위치는 `client-runtime.txt`, 네이티브 CLI의 상태 디렉터리 기준 상대 경로는 `native-client.txt`에 기록합니다.
  Gradle 플러그인이 OS·CPU를 선택하고 SHA-256별 디렉터리에 실행 파일을 설치합니다. Windows에서 실행 중인 파일을 덮어쓰지 않습니다.
  지원 플랫폼의 hook은 Rust CLI를 실행하고, 그 밖의 환경은 Java 클라이언트를 사용합니다.
- IDE 종료는 연결만 닫습니다. 명시적 stop 또는 기본 30분 idle 종료가 worker도 정리합니다.
- daemon은 Gradle을 실행하지 않습니다. IDE와 Rust CLI가 필요한 경우 Gradle start 태스크를 실행합니다.
  일반 Gradle apply 태스크는 기존처럼 Gradle 안에서 설정을 내보냅니다.
- 네이티브 요청은 `apply`와 `apply-refreshed`를 사용합니다. 프로젝트 root·GRADLE_USER_HOME·경로 목록은 길이 제한된 UTF-8 payload에 넣고,
  ASCII token/action/key만 기존 modified UTF-8 헤더를 사용합니다. 서버는 payload의 프로젝트 root도 검증합니다.
  상태 3은 파일 쓰기 전의 설정 갱신 요청입니다. CLI는 Gradle 성공 뒤 한 번만 재요청합니다.
  소스 오류·설정 오류·응답 유실/timeout은 자동 재시도하지 않습니다. daemon의 직렬 처리 루프에서 worker를 직접 호출하며 자신에게 TCP 요청하지 않습니다.
- 파일 저장은 Java의 기존 인코딩·설정 fingerprint·원본 bytes 확인을 재사용합니다.
  배치 전체를 포맷한 뒤 저장합니다. 저장 중 나중 파일에서 실패하면 이미 저장한 파일은 롤백하지 않습니다.
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
엔진 class major version은 61이며, Gradle Module Metadata의 JVM 버전은 17입니다.
같은 엔진 JAR를 모든 소비 JDK에서 사용합니다.
임의의 상위 JDK나 Java 25의 모든 문법 지원을 의미하지 않습니다.

Google Java Format은 JDK 17 테스트에서 1.28.0, JDK 21·25에서 1.30.0을 사용합니다.
JDK 17에서 1.30.0을 지정하면 실패하며 파일을 보존하는지, 1.28.0으로 복구하면 정상 포맷되는지도 검사합니다.
이 분기는 테스트 fixture에만 적용합니다. 운영 엔진은 프로젝트가 선택한 포매터 버전을 바꾸지 않습니다.
Gradle·Spotless 버전 정책 검사는 JDK 17·21의 Gradle 8.14.3 조합에서 실행합니다.

`check`는 `build/repository`에 marker와 구현 JAR를 게시하고, 임시 소비 프로젝트에서 실제로 해석합니다.
따라서 source classpath만 연결한 테스트와 달리 플러그인 패키징·배포 메타데이터도 경로에 포함됩니다.
전역 Maven 로컬 저장소는 변경하지 않습니다.

- 기존 `FormatterSelfTest`: Groovy/Kotlin DSL, Spotless 8.10.2/8.0.0, Palantir/Google/Eclipse,
  Gradle 결과 비교, 신규·제외 파일, 없는 파일 요청 후 생성, 설정 변경·실패·복구, 다중 모듈.
- `ProcessSelfTest`: 문법 오류, worker 재사용·강제 종료 후 복구, timeout, 종료.
- `SharedDaemonSelfTest`: Gradle 실행만으로 `.gradle/spotless-bridge/`에 hook 런처가 생기고 변경 시 복원되는지, 엔진 복구와 0.1.0 호출 방식을 확인한 뒤 편집기 연결 종료 후 hook 실행,
  설정이 같은 등록 대상 파일의 반복 hook에서 Gradle 호출 0회, 시작·설정 변경 시 1회, 공용 PID/worker, 80개 동시 요청, hook만의 시작, stale endpoint 복구, 잘못된 토큰 거부,
  UTF-16LE·CRLF Gradle byte 일치, 외부 includeBuild를 포함한 설정 갱신, 동시 시작, stop과 idle 종료.

엔진 CI 11개 조합은 SDK 없는 `check engineDist`를 실행합니다. 별도 IDE 빌드 job은 최소 지원 SDK인 2025.3으로
신뢰 회귀 테스트와 ZIP 생성을 수행합니다. 이어지는 Verifier job 여섯 개는 동일한 ZIP artifact를 받아
각 계열의 첫 버전·최신 패치를 검사하고 ZIP 해시를 전후 비교합니다. SDK 버전·빌드·Linux SHA-256은
`gradle/idea-compatibility.json`, Verifier 버전은 `gradle.properties`에서 관리합니다. 첫 SDK 항목이
최소 지원 빌드와 컴파일 SDK를 결정합니다. SDK 변경 시 해시·설치 허용 범위·검사 결과를 함께 확인하세요. Verifier는 문제를 발견해도 종료 코드 0을 반환할 수 있어
문제 리포트도 확인하며, 매번 이전 리포트를 지워 잘못된 성공 판정을 막습니다.
실제 IDE UI 저장 동작은 별도로 확인해야 합니다. IDE 검증 상세는
[소스 설치 안내](install-from-source.ko.md#게시-없이-동일-zip으로-여러-ide-검증)를 참고하세요.

`verify.yml`은 Ubuntu 조합에 더해 macOS에서 Gradle 8.14.3·JDK 17과 Gradle 9.8.1·JDK 21을 실행합니다. Windows x64·ARM64는 별도의 `native.yml`에서 JDK 21·Gradle 8.14.3으로 CLI와 런처를 검사합니다.
`spotless-latest.yml`은 매주 월요일 Plugin Portal의 최신 Spotless 버전을 찾아 `-PtestSpotlessVersion`으로 전체 검사를 실행합니다.
이 job이 실패하면 Spotless 내부 API 변경이나 허용 범위 밖 버전(예: 9.x)이 나온 것이므로 지원 범위와 구현을 점검하세요.

## 로컬 패키징

`com.gradle.plugin-publish`로 구현 JAR, sources/javadoc, plugin marker와 플러그인 메타데이터를 생성합니다.
`./gradlew publishAllPublicationsToTestRepository`는 로컬 파일 저장소에만 게시합니다. `-PspotlessBridgeRepository=/path/to/project/.gradle/spotless-bridge-repository`로 게시 위치를 지정할 수 있습니다.

라이선스는 MIT이며 `LICENSE`를 모든 JAR의 `META-INF`에 포함하고 Maven POM에도 기록합니다.
기본 사용 문서는 영문 `README.md`, 한국어 문서는 `README.ko.md`입니다. 동작이나 설치 방법이 바뀌면 둘 다 갱신합니다.

hook 런처(`src/main/resources/spotless-bridge/spotless-bridge`, `.cmd`)는 플러그인 JAR에 포함되고, 플러그인이 적용될 때마다
(빌드, IDE 동기화 등 모든 Gradle 실행) `.gradle/spotless-bridge/`에 씁니다. 내용이 같으면 다시 쓰지 않으며, 새로 쓰면
`client-runtime.txt`를 지워 다음 hook이 이 플러그인의 엔진을 준비하게 합니다(0.1.0 엔진은 `--hook`을 모르기 때문).
런처는 자기 위치에서 Gradle 루트를 찾고 지원 플랫폼에서는 native CLI로 요청을 보냅니다. native CLI는 준비가
필요할 때만 Gradle wrapper로 `spotlessBridgeStart`를 실행합니다. Windows 런처는 설치된 CLI가 사라졌을 때도
Gradle로 복원한 뒤 요청합니다. 그 밖의 Java 대체 경로는 `GradleFormatter --hook <root> <files...>`를 사용합니다.
0.1.0 스크립트가 쓰는 `<root> <list-file>` 호출 방식도 계속 지원합니다. Windows 런처는 native CI에서 검사합니다.

## Rust CLI 빌드와 검증

`native/`는 외부 crate 없는 Rust 1.90.0 클라이언트입니다.
기본 Gradle 빌드는 호스트용 실행 파일을 JAR에 포함합니다. 사용자의 컴퓨터에서는 Cargo를 실행하지 않습니다.
`.github/workflows/native.yml`이 macOS·Linux·Windows의 x64·ARM64에서 단위 테스트와 실제 소비 프로젝트 검사를 수행합니다.
Windows ARM64의 준비 단계는 Gradle 8.14.3의 환경변수 갱신 문제를 피하려고 `--no-daemon`을 사용합니다. warm 요청에는 영향이 없습니다.
Windows JVM의 Unicode 인자 손실을 피하려고 classpath는 상대 경로, worker의 snapshot 인자는 ASCII URI를 사용합니다.
daemon 종료는 worker와 JVM의 실제 종료까지 기다려 Windows의 JAR 파일 잠금을 해제합니다.
Linux는 musl로 정적 링크합니다. CI는 여섯 플랫폼의 CLI를 포함한 JAR로 JDK·Gradle 매트릭스를 검사합니다.
플랫폼별 검사 결과는 해당 CI 실행에서 확인합니다.

## Plugin Portal·Maven Central 릴리스

`release.yml`은 `v<version>` 태그를 push하면 네이티브 클라이언트 여섯 개를 빌드하고,
`check engineDist` 검증과 서명 ZIP 생성을 마친 뒤 Plugin Portal, Maven Central 순서로 배포합니다.
같은 작업 디렉터리의 구현 JAR와 plugin marker를 사용합니다. Central은 공식 Publisher API의
`AUTOMATIC` 방식으로 업로드하며, `PUBLISHED`까지 확인한 후 GitHub Release를 생성합니다.
서명 ZIP은 `central-bundle-<version>` Actions artifact에도 보관합니다.

처음 한 번 저장소의 **Settings → Secrets and variables → Actions → New repository secret**에 등록하세요.

| Secret | 값 |
| --- | --- |
| `GRADLE_PUBLISH_KEY` | 기존 Plugin Portal API key |
| `GRADLE_PUBLISH_SECRET` | 기존 Plugin Portal API secret |
| `MAVEN_CENTRAL_USERNAME` | Central에서 생성한 User Token의 username |
| `MAVEN_CENTRAL_PASSWORD` | 같은 User Token의 password |
| `SIGNING_KEY` | `-----BEGIN PGP PRIVATE KEY BLOCK-----`부터 끝 줄까지 포함한 ASCII-armored 개인키 전체 |
| `SIGNING_PASSWORD` | 개인키 암호 |

Central 로그인 이메일·비밀번호가 아니라 [User Token](https://central.sonatype.com/usertoken)을 사용합니다.
개인키는 `gpg --armor --export-secret-keys <전체 지문>`으로 내보낸 내용을 등록합니다.
공개키는 Central이 지원하는 키 서버에 미리 등록해야 합니다. 개인키나 암호를 저장소 파일에 넣지 않습니다.

릴리스할 때는 `gradle.properties`, `native/Cargo.toml`, `native/Cargo.lock`의 버전을 맞추고 `CHANGELOG.md`에 해당 버전의
변경 내역을 작성합니다. 배포 전에는 `Unreleased`로 두고, 실제 릴리스할 때 배포 날짜로 바꿉니다.
IntelliJ 업데이트 노트는 `src/idea/resources/META-INF/plugin.xml`의 `change-notes`에 작성합니다.
워크플로 변경과 버전 변경을 커밋·push한 뒤 해당 커밋에 태그를 붙입니다.
예를 들어 다음 새 버전은 아래처럼 배포합니다.

```sh
git tag v0.2.2
git push origin v0.2.2
```

태그 push는 양쪽 저장소에 실제 공개 배포합니다. 이미 공개된 버전의 태그를 옮기거나 같은 버전을 덮어쓰지 않습니다.
수동 `Run workflow`는 `publish=false`가 기본이며, 검증·서명 ZIP 생성과 Plugin Portal의 `--validate-only`만
수행합니다. Central 업로드나 공개 배포는 하지 않으며 Central 서버의 인증·검증을 확인하는 것도 아닙니다.

두 저장소의 배포는 하나의 트랜잭션이 아닙니다. 한쪽만 성공하면 해당 버전 태그에서 실패한 저장소만 재실행하세요.
예를 들어 Plugin Portal은 성공하고 Central만 실패했다면:

```sh
gh workflow run release.yml --ref v0.2.2 -f publish=true -f repository=maven-central
```

반대의 경우 `repository=plugin-portal`을 사용합니다. 재실행 전에 이미 성공한 쪽의 상태를 확인하세요.
네트워크 오류나 시간 초과라면 Central에 업로드가 접수되었거나 계속 배포 중일 수 있으므로,
Actions 로그·요약의 deployment ID와 [Central Portal](https://central.sonatype.com/publishing)을 먼저 확인합니다.
`PUBLISHED`인 버전을 다시 올리지 않습니다. 복구 실행이 성공하면 GitHub Release도 생성하거나 갱신합니다.

API 인증·업로드·상태 값은 [Central 공식 API 문서](https://central.sonatype.org/publish/publish-portal-api/)를 따릅니다.
