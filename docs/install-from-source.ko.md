# 소스에서 Spotless Bridge 설치하기

[English](install-from-source.md) | 한국어

Gradle Plugin Portal과 JetBrains Marketplace에 배포되기 전에 Spotless Bridge를 써 보거나,
Bridge 자체를 개발할 때 참고하는 문서입니다. 일반적인 사용법은 [README](../README.ko.md)를 참고하세요.

Spotless Bridge 소스 디렉터리와 **JDK 21**이 필요합니다. 예시에서는 다음 경로를 실제 위치로 바꿔 사용하세요.

- `/path/to/spotless-bridge`: Spotless Bridge 소스 디렉터리
- `/path/to/jdk21`: JDK 21 설치 경로

## 네이티브 CLI 빌드 준비 (0.2.0)

소스에서 빌드할 때는 rustup으로 Rust를 준비합니다. `native/rust-toolchain.toml`에 Rust 1.90.0이 고정되어 있고,
Gradle 리소스 처리 시 Cargo가 현재 컴퓨터용 CLI를 빌드합니다. 외부 crate 의존성은 없습니다.
이 요구사항은 Bridge 개발자에게만 해당합니다. 게시된 플러그인을 사용하는 사람은 Rust를 설치하지 않습니다.
이 체크아웃의 로컬 저장소를 사용할 소비 프로젝트에서는 버전을 `0.2.0`으로 지정하세요.

```sh
./gradlew testNativeCli nativeSmokeTest
```

로컬 빌드에는 현재 OS·CPU 실행 파일만 포함됩니다. 정식 배포 때는 여섯 CI artifact의 플랫폼 폴더를
`build/native` 아래에 모으고 `-PnativeCliDirectory=build/native`를 지정합니다.
`publishPlugins`는 `--validate-only`에서도 여섯 실행 파일 중 하나라도 없으면 중단합니다.
artifact를 받은 다음 `clean`으로 지우지 마세요.

## 1. Gradle 플러그인을 로컬 저장소에 게시

Spotless Bridge 소스 디렉터리에서 실행합니다.

```sh
cd /path/to/spotless-bridge
export JAVA_HOME=/path/to/jdk21
./gradlew publishAllPublicationsToTestRepository
```

게시된 플러그인은 `build/repository`에 저장됩니다.
사용할 프로젝트의 `settings.gradle` 맨 앞에 이 경로를 추가하세요.

```groovy
pluginManagement {
    repositories {
        maven { url = uri('/path/to/spotless-bridge/build/repository') }
        gradlePluginPortal()
    }
}
```

`settings.gradle.kts`에서는 작은따옴표 대신 큰따옴표를 사용합니다.
그다음 README의 [빠른 시작](../README.ko.md#빠른-시작)에 따라 플러그인을 적용하고 AI 도구를 연결합니다.
hook 런처는 다음 Gradle 실행 때 `.gradle/spotless-bridge/`에 생기므로 따로 설치할 것은 없습니다.
Plugin Portal에 배포된 뒤에는 로컬 `maven` 항목을 제거하세요.

## 2. IntelliJ 플러그인 빌드와 설치

최소 지원 IDE인 **2025.3 (IU-253.28294.334)** SDK와 JDK 21로 빌드합니다.
고정 버전·정확한 빌드 번호·Linux 배포본 SHA-256은
[`gradle/idea-compatibility.json`](../gradle/idea-compatibility.json)에 있으며, 첫 항목이 빌드용 SDK입니다.
`https://download.jetbrains.com/idea/idea-<version>.tar.gz`를 받아 해당 파일의 해시와 대조한 뒤,
`--strip-components=1`로 `<sdk-root>/<version>`에 압축을 풉니다.
기본 SDK 루트는 이 저장소의 `.gradle/idea-sdks`이며 SDK 배포본은 커밋하지 않습니다.
macOS에서도 Linux 배포본을 SDK 입력으로 쓸 수 있습니다. 컴파일·테스트는 로컬 JDK 21에서 실행하고,
Verifier는 IDE를 실행하지 않고 클래스와 동봉 런타임을 읽습니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew ideaTest pluginZip -PideaSdkRoot=/path/to/sdk-root
```

빌드에는 2025.3 SDK만 필요합니다. `-PideaHome=/path/to/IDE`로 빌드 SDK 위치를 바꿀 수 있으며,
macOS IDE는 `Contents` 경로를 사용합니다. 제품·버전·빌드가 다르면 `verifyIdeaSdk`가 중단합니다.
adapter 바이트코드는 Java 21, 엔진은 Java 17입니다.
ZIP 설치 허용 범위는 `253.28294.334`부터 `262.*`까지로, 2025.3~2026.2를 대상으로 합니다.

IntelliJ의 **Settings → Plugins → 톱니바퀴 → Install Plugin from Disk…**에서
`build/distributions/spotless-bridge-0.2.0.zip`을 선택하세요. 이전 개발판이 있다면 먼저 비활성화하거나 제거합니다.
이후 [IntelliJ IDEA 설정](../README.ko.md#intellij-idea-미리보기)을 따릅니다.

### 게시 없이 동일 ZIP으로 여러 IDE 검증

고정한 SDK 여섯 개를 SDK 루트에 준비하고 실행합니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew verifyIdeaPlugin -PideaSdkRoot=/path/to/sdk-root
```

신뢰 회귀 테스트 후 2025.3으로 ZIP을 한 번 만들고, Plugin Verifier **1.410**으로 같은 ZIP을 검사합니다.
각 지원 계열의 첫 버전과 고정한 최신 패치가 대상입니다.

| IntelliJ IDEA | Build |
| --- | --- |
| 2025.3 | `253.28294.334` |
| 2025.3.6.1 | `253.33813.55` |
| 2026.1 | `261.22158.277` |
| 2026.1.5 | `261.27258.48` |
| 2026.2 | `262.8665.258` |
| 2026.2.3 | `262.10968.63` |

2026년 10월 16일, 0.1.1 ZIP으로 위 여섯 검사를 통과했습니다. 2025.3 계열은 `Compatible`,
2026.1·2026.2 계열은 deprecated API 사용 3건을 포함한 `Compatible` 판정입니다. 경고의 이유는 아래에 설명합니다.

이미 만든 ZIP을 재빌드 없이 특정 버전에서만 검증하려면 다음처럼 실행합니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew verifyIdeaPlugin \
  -PideaPluginZip=/absolute/path/spotless-bridge-0.2.0.zip \
  -PideaVerifyVersion=2026.2.3 -PideaSdkRoot=/path/to/sdk-root
```

이 명령에는 해당 버전 SDK만 필요합니다. 보고서는 `build/reports/plugin-verifier/`에 있으며
`plugin-sha256.txt`에 검사한 ZIP 해시를 남깁니다. SDK 누락·빌드 불일치·판정 누락·바이너리 호환성 오류·
금지된 API 사용은 실패 처리합니다. 검사 중 ZIP 내용이 바뀌어도 실패합니다.
새 IDE에서는 공개 API인 `ReadAction.compute/run`의 deprecated 경고가 나옵니다. 대체 `*Blocking` API가
2025.3에는 없어 호환성을 위해 기존 API를 유지하며, 경고를 숨기거나 리플렉션으로 우회하지 않습니다.

CI는 ZIP을 한 번 빌드하고 여섯 Verifier job이 같은 artifact를 받아 검사 전후 SHA-256을 확인합니다.
SDK 다운로드 해시도 확인합니다. 기존 엔진 11개 job은 SDK 없이 실행하며 Marketplace에 자동 게시하지 않습니다.

Safe Mode에서는 캐시된 연결을 포함한 포맷 요청을 차단합니다. 신뢰는 사용자가 IntelliJ UI에서 부여해야 합니다.
회귀 테스트는 신뢰 응답을 모킹합니다. 바이너리 검증은 실제 IDE의 Safe Mode·Reformat Code·저장 동작·
공용 daemon PID 확인을 대신하지 않습니다. 중간 패치와 미래 버전 각각을 모두 시험한 것은 아니며,
검증 목록을 늘릴 때 고정 매트릭스를 갱신하세요.

## 개발

JDK 21로 엔진과 Gradle 플러그인 검사를 실행합니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist
```

같은 검사를 다른 소비 프로젝트 Gradle 버전으로 실행하려면 `testGradleVersion`을 지정합니다. 기본값은 `8.14.3`입니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist -PtestGradleVersion=9.8.1
```

컴파일은 JDK 21로 하고 전체 호환성 검사를 JDK 17로 실행하려면 다음과 같이 지정합니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist \
  -PtestGradleVersion=8.14.3 -PtestJavaHome=/path/to/jdk17
```

JDK 25는 Gradle 9.8.1과 함께 검사합니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew check engineDist \
  -PtestGradleVersion=9.8.1 -PtestJavaHome=/path/to/jdk25
```

`testJavaHome`을 지정하지 않으면 빌드 JVM의 홈 경로를 사용합니다. 이 값은 hook 최초 실행 시의 `JAVA_HOME`에도 적용되며,
시스템 기본 Java나 엔진의 최소 실행 버전은 바꾸지 않습니다.

다른 소비 프로젝트 Spotless 버전으로 검사하려면 `-PtestSpotlessVersion=<버전>`을 추가합니다. 기본값은 `8.10.2`입니다.
CI는 두 조합을 macOS에서도 실행하고, 매주 최신 Spotless로 전체 검사를 돌려 Bridge가 의존하는
Spotless 내부 API의 변경을 미리 확인합니다.

Bridge 자체는 JDK 21과 저장소에 포함된 Gradle 8.14.3 wrapper로 빌드합니다. 테스트에서는 Java 17 대상으로 빌드한
같은 엔진을 선택한 소비 프로젝트 JDK로 실행하며, Gradle·daemon·worker·hook 모두 이 JDK를 사용합니다.
Ubuntu CI는 JDK 17·21에서 각각 네 Gradle 버전을, JDK 25에서 Gradle 9.8.1을 검사합니다.
[호환 조합 표](../README.ko.md#요구-사항과-호환성)의 아홉 조합 전체입니다.
Google Java Format 테스트 버전은 JDK 17에서 1.28.0, JDK 21·25에서 1.30.0으로 고정합니다.
엔진과 엔진 테스트는 `--release 17`로 컴파일하며, IntelliJ adapter와 해당 테스트는 JDK 21에서 `--release 21`로 컴파일합니다.
포맷용 외부 JDK 17을 선택하는 것과 IntelliJ 자체 실행 JDK의 요구사항은 별개입니다.

IntelliJ SDK는 IDE 패키징·검증 태스크에 필요하며, 엔진 검사에는 필요하지 않습니다. 구현 상세, 회귀 검사, 게시 절차는 [유지보수 문서](maintenance.md)를 참고하세요.
