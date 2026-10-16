# 소스에서 Spotless Bridge 설치하기

[English](install-from-source.md) | 한국어

Gradle Plugin Portal과 JetBrains Marketplace에 배포되기 전에 Spotless Bridge를 써 보거나,
Bridge 자체를 개발할 때 참고하는 문서입니다. 일반적인 사용법은 [README](../README.ko.md)를 참고하세요.

Spotless Bridge 소스 디렉터리와 **JDK 21**이 필요합니다. 예시에서는 다음 경로를 실제 위치로 바꿔 사용하세요.

- `/path/to/spotless-bridge`: Spotless Bridge 소스 디렉터리
- `/path/to/jdk21`: JDK 21 설치 경로

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

소스 디렉터리에서 IDE용 ZIP을 빌드합니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew pluginZip
```

IntelliJ SDK는 `gradle.properties`에 **2026.2.3 (IU-262.10968.63)**으로 고정합니다.
기본 경로는 `/Applications/IntelliJ IDEA.app/Contents`입니다. 경로를 바꾸더라도 제품·버전·빌드가
다르면 `verifyIdeaSdk`가 컴파일 전에 중단합니다. JBR이 포함된 전체 IDE 배포본을 사용하세요.
이 SDK에는 Java 25 클래스가 있으므로 IDE 컴파일·테스트는 SDK에 포함된 JBR을 사용하고,
Gradle 자체는 JDK 21로 실행합니다. adapter 바이트코드는 Java 21, 엔진은 Java 17을 유지합니다.
IntelliJ가 macOS의 다른 경로에 설치되어 있다면 `-PideaHome=/path/to/IntelliJ/Contents`를 추가하세요.
ZIP의 설치 허용 범위는 `262.10968.63`부터 `262.*`이며, 검증 대상은 고정한 SDK입니다.
이후 262 빌드까지 검증한 것으로 간주하지 않으며, 지원 검증 목록을 늘릴 때 별도로 확인합니다.

IntelliJ의 **Settings → Plugins → 톱니바퀴 → Install Plugin from Disk…**에서
`build/distributions/spotless-bridge-0.1.1.zip`을 선택합니다. 이전 개발판 플러그인을 쓰고 있었다면
먼저 비활성화하거나 제거하세요. 이후 설정은 README의 [IntelliJ IDEA 설정](../README.ko.md#intellij-idea-미리보기)을 따릅니다.

### 게시 없이 IntelliJ adapter 검증

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew verifyIdeaPlugin -PideaHome=/path/to/pinned/IDE
```

SDK 확인, Safe Mode 회귀 테스트, ZIP 생성, Plugin Verifier **1.410** 검사를 실행합니다.
리포트는 `build/reports/plugin-verifier/`와 `build/reports/tests/ideaTest/`에 있습니다.
바이너리 호환성 오류·잘못된 플러그인·금지된 API 사용은 태스크를 실패시킵니다. 경고는 리포트에
그대로 남으며, 검증 성공은 Marketplace 승인이나 실제 IDE UI 동작 검증을 의미하지 않습니다.
CI의 별도 Ubuntu IDE job은 고정 SDK를 내려받아 SHA-256을 확인합니다. 기존 엔진 11개 job에는
SDK가 필요하지 않으며 이 검증 명령은 어떤 게시도 수행하지 않습니다.

Safe Mode에서는 포맷을 실행하지 않습니다. IntelliJ UI에서 프로젝트를 직접 신뢰한 뒤 사용하세요.
플러그인은 신뢰를 자동으로 부여하지 않으며, 캐시된 포매터 사용 전에도 신뢰를 다시 확인합니다.
자동 테스트는 IDE의 신뢰 응답을 모킹해 요청 차단을 확인합니다. 실제 Safe Mode UI, Reformat Code,
저장 동작과 공용 daemon PID는 출시 전 깨끗한 IDE에서 별도로 확인하세요.

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
엔진과 엔진 테스트는 `--release 17`로 컴파일하며, IntelliJ adapter와 해당 테스트는 고정 SDK의 JBR에서 `--release 21`로 컴파일합니다.
포맷용 외부 JDK 17을 선택하는 것과 IntelliJ 자체 실행 JDK의 요구사항은 별개입니다.

IntelliJ SDK는 IDE 패키징·검증 태스크에 필요하며, 엔진 검사에는 필요하지 않습니다. 구현 상세, 회귀 검사, 게시 절차는 [유지보수 문서](maintenance.md)를 참고하세요.
