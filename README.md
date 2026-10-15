# Spotless Daemon

프로젝트의 Spotless 설정과 라이브러리를 그대로 사용하는 상주 Java 포매터입니다.
**IntelliJ와 AI hook이 Gradle 프로젝트별 공용 daemon을 사용합니다.** IDE가 꺼져 있어도
hook이 daemon을 시작하고 재사용합니다. 전역 설치나 OS 시작 서비스는 필요하지 않습니다.

현재 버전은 **0.5.0 개발 버전**이며 Gradle Plugin Portal·JetBrains Marketplace에는 아직 게시하지 않았습니다.

## 프로젝트 설정

루트 `build.gradle`에 daemon 플러그인을 적용하고, 기존 Spotless 규칙을 유지합니다.
아래 버전은 예시이며 Spotless Daemon이 Spotless나 포매터 버전을 강제하지 않습니다.

```groovy
plugins {
    id 'java'
    id 'com.diffplug.spotless' version '8.10.2'
    id 'io.github.pozafly.spotlessdaemon' version '0.5.0'
}
repositories { mavenCentral() }
spotless {
    java {
        shortenFullyQualifiedTypes()
        removeUnusedImports()
        palantirJavaFormat('2.97.0')
    }
}
```

Kotlin DSL에서는 `id("io.github.pozafly.spotlessdaemon") version "0.5.0"`을 사용합니다.
다중 모듈도 daemon 플러그인은 루트에 한 번 적용합니다. 각 모듈의 Spotless 설정은 따로 유지됩니다.
JDK 21과 Gradle wrapper가 필요합니다. 현재 자동 검증 기준은 Gradle 8.14.3입니다.

### 공개 배포 전 로컬 사용

이 저장소에서 `./gradlew publishAllPublicationsToTestRepository`를 실행하면
`build/repository`에 구현 JAR와 플러그인 marker가 생성됩니다. 전역 Maven 저장소에는 설치하지 않습니다.
사용할 프로젝트의 `settings.gradle`에 해당 **로컬 저장소 경로**를 지정합니다.

```groovy
pluginManagement {
    repositories {
        maven { url = uri('/path/to/spotless-daemon/build/repository') }
        gradlePluginPortal()
    }
}
```

이 경로는 개발 중에만 필요합니다. Plugin Portal 게시 후에는 로컬 저장소 선언을 제거합니다.
현재 공개 저장소만으로 `0.5.0`을 다운로드할 수는 없습니다.

## AI hook

권장 hook 진입점은 다음 스크립트입니다. 명시한 변경 파일만 처리하고, 공백·쉼표가 있는 경로도 보존합니다.

```sh
python3 /path/to/spotless-daemon/scripts/ai-hook.py /path/to/project src/main/java/Changed.java
```

첫 실행에는 Gradle로 프로젝트가 선택한 엔진을 설치합니다. 이후에는 프로젝트의
`.gradle/spotless-daemon/client-runtime.txt`가 가리키는 클라이언트를 직접 실행합니다.
클라이언트는 현재 설정·파일 대상·연결을 확인하고 **그대로면 Gradle을 실행하지 않고 daemon에 요청**합니다.
daemon이 없거나 설정이 바뀌거나 새 파일의 대상 확인이 필요할 때만 Gradle로 시작·갱신합니다.

Gradle 태스크로 직접 사용하는 경로도 있습니다. 파일 목록은 UTF-8, 한 줄에 경로 하나이며 상대 경로는
Gradle 루트 기준입니다. 이 명령은 호출할 때마다 Gradle을 평가하므로 반복 hook에는 위 스크립트를 권장합니다.

```sh
./gradlew --no-configuration-cache spotlessDaemonApply -PspotlessDaemonFiles=/path/to/changed-files.txt
```

목록 인자를 빠뜨리면 실패하며 전체 소스로 적용 범위를 넓히지 않습니다.
종료 코드가 0이 아니면 hook도 실패로 처리해야 합니다.
OpenRewrite를 함께 사용하면 `Rewrite → daemon 포맷 → spotlessCheck·컴파일·필요한 테스트` 순서로
연결합니다. 포맷만 Gradle을 생략하며 Rewrite·검사·컴파일 태스크는 기존대로 실행합니다.
포맷 성공이 전체 테스트 성공을 의미하지는 않습니다.

## IntelliJ

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew clean build pluginZip
```

`build/distributions/spotless-daemon-0.5.0.zip`은 **IDE 연결 코드만** 포함합니다.
실행 엔진 버전은 프로젝트의 Gradle 플러그인이 소유합니다.
IDE SDK는 ZIP 빌드에만 필요하며 `-PideaHome=/path/to/IntelliJ/Contents`로 지정할 수 있습니다.
현재 SDK 대상은 IntelliJ IDEA 262 계열입니다.

실제 설치와 설정 변경은 사용자가 수행합니다.

1. Settings → Plugins → 톱니바퀴 → Install Plugin from Disk…에서 ZIP을 선택합니다.
2. Settings → Tools → Spotless Daemon에서 사용을 켜고 JDK 21 경로를 지정합니다.
3. 필요한 경우 Gradle 루트와 추가 변경 감지 경로를 지정합니다.
4. Tools → Actions on Save → Reformat code에서 Java / Whole file을 선택합니다.
   겹치는 Java 포매터, Optimize imports·Rearrange code·Code cleanup은 끕니다.

Reformat Code와 저장 시 포맷은 문서 내용을 daemon에 보내고, 최신 문서에만 결과를 반영합니다.
IDE에 반환할 때만 LF로 바꿉니다. hook의 디스크 저장은 Spotless 인코딩·줄바꿈 정책을 따릅니다.
IDE를 닫거나 설정을 끄면 해당 연결만 닫습니다. 공용 daemon은 다른 hook이 계속 사용할 수 있습니다.
**다음 포맷 시 설정 다시 불러오기**는 연결의 캐시를 비웁니다.

## 동작과 수명

| 구성 | 역할 |
| --- | --- |
| 프로젝트 Gradle | Spotless 설정·대상 평가, 라이브러리 다운로드, daemon 엔진 선택 |
| `GradleFormatter` | IDE 연결, 설정 변경·새 파일 감지 시 Gradle 재평가 요청 |
| `SharedDaemon` | 루프백 연결, 요청 직렬 처리, 프로젝트별 수명 관리 |
| 내부 worker | 프로젝트 라이브러리로 포맷 수행, 오류·시간 초과 시 격리 및 재시작 |

프로젝트의 canonical Gradle 루트마다 공용 daemon 하나가 존재합니다.
내부 worker 하나는 daemon이 소유합니다. IntelliJ용·hook용 worker를 따로 만들지 않습니다.
설정이 그대로면 daemon과 worker를 모두 재사용합니다. 설정이나 파일 대상 목록이 바뀌면
같은 daemon 안에서 worker를 교체합니다. 엔진 JAR나 실행 JDK가 바뀌면 daemon도 교체합니다.

Gradle 태스크는 설정을 직접 내보내므로 daemon에서 Gradle을 다시 호출하지 않습니다.
태스크와 daemon이 서로 기다리는 중첩 Gradle 실행을 피합니다.
IntelliJ의 기존 파일 반복 요청은 설정이 같으면 Gradle을 실행하지 않습니다.
hook 클라이언트의 기존 파일 반복 요청도 설정이 같으면 Gradle을 생략합니다.

```sh
./gradlew spotlessDaemonStart    # 현재 설정으로 시작 또는 갱신
./gradlew spotlessDaemonStatus   # PID, 실행 중이 아니면 -1
./gradlew spotlessDaemonStop     # 명시적 종료
```

- 사용하지 않은 지 30분이 지나면 daemon과 worker가 종료됩니다.
- 중복 시작은 파일 잠금으로 막고, 종료된 daemon의 남은 연결 정보는 다음 시작에서 교체합니다.
- 로컬 루프백 TCP와 프로젝트별 인증 토큰을 사용합니다. 외부 네트워크에는 바인딩하지 않습니다.
- 요청을 순서대로 처리하여 동시에 여러 hook·IDE가 요청해도 같은 포매터에 병렬 접근하지 않습니다.
- 상태·로그는 `.gradle/spotless-daemon/`의 `endpoint.properties`, `daemon.log`, `worker.log`,
  `gradle-start-*.log`, `client-runtime.txt`, `snapshots/`, `runtime/`에 있습니다. Git에 포함하지 마세요.
- 삭제가 필요하면 먼저 `spotlessDaemonStop`을 실행한 뒤 위 디렉터리를 삭제합니다.

## 변경 감지와 제한

빌드 스크립트, Gradle properties, wrapper, `gradle/`, `buildSrc/`, `build-logic/`, TOML,
`.editorconfig`, `.gitattributes`, 포함 빌드·외부 프로젝트, Gradle 사용자 홈 초기화 설정을 감지합니다.
IDE에서 별도로 지정한 추가 경로도 감지합니다. 없는 파일은 제외 캐시에 넣지 않아 첫 저장 후 다시 평가합니다.

환경 변수·원격 데이터 등 모든 Gradle 입력을 자동 추적하지는 않습니다.
동적으로 변한 입력은 `spotlessDaemonStart`로 명시적으로 갱신하세요.
동시에 서로 다른 `-P` 옵션을 사용하는 도구는 마지막으로 갱신한 설정을 공유합니다.
같은 프로젝트를 사용할 때 Gradle 속성·환경을 맞추세요.

- Java 전체 파일만 지원합니다. Maven, 다른 언어, 선택 영역 포맷은 지원하지 않습니다.
- Spotless 내부 API와 직렬화를 사용합니다. 모든 버전·사용자 정의 단계를 보장하지 않습니다.
- `ratchetFrom`과 직렬화 불가 단계는 실패합니다. 실패 시 과거 설정으로 대신 포맷하지 않습니다.
- configuration cache·isolated projects는 지원 대상으로 선언하지 않습니다.
- 설정 평가 120초, worker 포맷 15초, TCP 응답 대기 30초, UTF-8 메시지 최대 16 MiB입니다.
- hook은 포맷 중 파일·설정이 달라지면 덮어쓰기 전에 실패합니다. 외부 편집기와의 원자적 잠금은 보장하지 않습니다.
- 기본 정책 밖에 생성되는 소스는 해당 생성 태스크를 먼저 실행해야 합니다.

## 검증과 배포

`./gradlew check`는 IDE 없이 로컬 Maven 저장소에 플러그인을 게시한 후 실제 소비 프로젝트에서 실행합니다.
Spotless 8.10.2/8.0.0, Palantir·Google·Eclipse, Groovy/Kotlin DSL, 다중 모듈,
제외 캐시·신규 파일, 설정 변경·실패·복구, 일반 Spotless 결과 일치,
공용 daemon 재사용·동시 요청·재시작·종료·hook 실행과 반복 hook의 실제 Gradle 호출 0회를 확인합니다.
실제 IDE 저장 UI, Plugin Verifier, Marketplace 심사는 별도 검증입니다.

배포 절차와 내부 API 주의점은 [유지보수 문서](docs/maintenance.md)를 참고하세요.
