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
그다음 README의 [빠른 시작](../README.ko.md#빠른-시작)에 따라 플러그인을 적용합니다.
Plugin Portal에 배포된 뒤에는 로컬 `maven` 항목을 제거하세요.

## 2. AI hook 런처 설치

로컬에 게시한 플러그인을 적용한 뒤, 사용할 프로젝트의 Gradle 루트에서 hook 런처를 생성합니다.

```sh
./gradlew spotlessBridgeInstallHook
```

생성된 파일을 프로젝트에 커밋하세요. 플러그인을 다시 게시한 뒤 런처를 갱신하라는 경고가 나오면 이 태스크를 다시 실행합니다.
AI 도구에 연결하는 방법은 README의 [AI 도구 연결](../README.ko.md#3-ai-도구-연결)을 참고하세요.

## 3. IntelliJ 플러그인 빌드와 설치

소스 디렉터리에서 IDE용 ZIP을 빌드합니다.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew pluginZip
```

빌드에는 `/Applications/IntelliJ IDEA.app/Contents`의 IntelliJ SDK를 사용합니다.
IntelliJ가 macOS의 다른 경로에 설치되어 있다면 `-PideaHome=/path/to/IntelliJ/Contents`를 추가하세요.
ZIP은 IntelliJ IDEA 빌드 `262.10968`부터 `262.*`까지 설치할 수 있습니다.

IntelliJ의 **Settings → Plugins → 톱니바퀴 → Install Plugin from Disk…**에서
`build/distributions/spotless-bridge-0.1.1.zip`을 선택합니다. 이전 개발판 플러그인을 쓰고 있었다면
먼저 비활성화하거나 제거하세요. 이후 설정은 README의 [IntelliJ IDEA 설정](../README.ko.md#intellij-idea-미리보기)을 따릅니다.

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
엔진과 테스트는 `--release 17`로 컴파일하며, IntelliJ adapter는 IDE SDK에 맞춰 `--release 21`을 유지합니다.
포맷용 외부 JDK 17을 선택하는 것과 IntelliJ 자체 실행 JDK의 요구사항은 별개입니다.

IntelliJ SDK는 `pluginZip`에만 필요합니다. 구현 상세, 회귀 검사, 게시 절차는 [유지보수 문서](maintenance.md)를 참고하세요.
