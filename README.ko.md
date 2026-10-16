# Spotless Bridge

[English](README.md) | 한국어

**AI가 코드를 고칠 때도, IntelliJ에서 저장할 때도 매번 Gradle을 기다리지 않고 프로젝트의 Spotless 규칙으로 Java를 포맷합니다.**

Spotless Bridge는 프로젝트의 Spotless 포매터를 작은 백그라운드 daemon에 띄워 둡니다.
AI hook과 IntelliJ 플러그인은 `spotlessApply`를 실행하는 대신 이 daemon에 파일을 보내므로,
반복해서 포맷할 때 Gradle 설정 평가와 포매터 초기화를 건너뜁니다.

규칙과 포매터 버전은 기존 `spotless { java { ... } }` 설정을 그대로 따릅니다.
IDE 포맷 규칙을 따로 맞추거나 전역 서비스를 설치할 필요가 없습니다.

## 왜 만들었나요?

IntelliJ에서 파일을 저장하거나 AI가 코드를 생성할 때마다 Spotless로 포맷을 맞추고 싶었습니다.
그런데 매번 `spotlessApply`를 호출하면 파일 하나만 바꿔도, Gradle daemon이 떠 있어도
Gradle 설정 평가와 태스크 실행을 기다려야 했습니다.

프론트엔드 도구는 이 문제를 상주 프로세스로 풉니다. TypeScript의
[tsserver](https://github.com/microsoft/TypeScript/wiki/Standalone-Server-%28tsserver%29)는 계속 떠 있으면서
에디터 요청에 응답하고, [Prettier VS Code 확장](https://github.com/prettier/prettier-vscode)은 프로젝트에 설치된
Prettier를 불러와 포맷합니다. Spotless Bridge는 같은 아이디어를 Spotless에 적용해,
**메모리를 조금 더 쓰더라도 포맷할 때마다 기다리지 않게** 합니다.

## 빠른 시작

Spotless Java 포맷이 설정된 Gradle 프로젝트와 해당 프로젝트의 Gradle wrapper가 필요합니다.
최소 버전은 **Gradle 8.14.3 · Spotless 8.0.0 · JDK 17**이며, 허용 범위는
[요구 사항과 호환성](#요구-사항과-호환성)을 참고하세요.

### 1. 플러그인 적용

**루트** `build.gradle`의 `plugins` 블록에 Spotless와 함께 Spotless Bridge를 추가합니다.

```groovy
plugins {
    id 'com.diffplug.spotless' version '8.10.2'   // 기존 Spotless
    id 'io.github.pozafly.spotlessbridge' version '0.1.1'
}
```

Kotlin DSL에서는 `id("io.github.pozafly.spotlessbridge") version "0.1.1"`을 추가합니다.
기존 Spotless 버전과 규칙은 그대로 두세요. 다중 프로젝트 빌드에서는 Bridge를 루트에 한 번만 적용하고,
각 모듈의 Spotless 설정은 그대로 둡니다.

### 2. 프로젝트 포맷

```sh
./gradlew spotlessBridgeApply
```

Spotless Java 대상 파일 전체를 포맷하며, 결과는 `spotlessApply`와 같습니다.
첫 실행에서 daemon이 자동으로 시작되므로 따로 시작할 필요가 없습니다.

### 3. AI 도구 연결

플러그인은 `.gradle/spotless-bridge/`에 작은 실행 스크립트(런처)를 두고, 빌드나 IntelliJ의 Gradle 동기화처럼
Gradle이 실행될 때마다 최신 상태로 맞춥니다. 따로 설치하거나 커밋할 것은 없습니다.
AI 도구가 Java 파일을 수정한 뒤 수정한 파일을 넘겨 이 런처를 실행하도록 설정하세요.

```sh
JAVA_HOME=/path/to/jdk .gradle/spotless-bridge/spotless-bridge \
  src/main/java/example/Customer.java \
  src/test/java/example/CustomerTest.java
```

`JAVA_HOME`은 JDK 17 이상이어야 하며, 런처가 Gradle로 엔진을 준비할 때만 사용합니다.
Windows에서는 `.gradle\spotless-bridge\spotless-bridge.cmd`를 사용합니다.

예를 들어 Claude Code에서는 `.claude/settings.json`에 `PostToolUse` hook을 추가하면,
Claude가 Java 파일을 수정할 때마다 바로 포맷합니다. 이 예시는 `jq`로 수정한 파일 경로를 읽으며,
Claude Code 프로젝트 디렉터리가 Gradle 루트라고 가정합니다.

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "jq -r '.tool_input.file_path // empty' | grep '\\.java$' | tr '\\n' '\\0' | JAVA_HOME=/path/to/jdk xargs -0 \"$CLAUDE_PROJECT_DIR/.gradle/spotless-bridge/spotless-bridge\""
          }
        ]
      }
    ]
  }
}
```

다른 AI 도구도 같은 방식입니다. 작업에서 변경한 Java 파일을 모아 런처에 넘기면 됩니다.
설정은 여기까지입니다. 첫 요청에서 daemon이 시작되고, 이후 요청은 같은 daemon을 재사용합니다.

## 어떻게 동작하나요?

```text
프로젝트의 Gradle Spotless 설정
              |
              v
      프로젝트 공용 포맷 daemon
          /              \
         /                \
 IntelliJ 플러그인         AI hook
 Reformat / 저장         변경한 파일
```

1. 첫 요청에서는 프로젝트의 Gradle wrapper로 Spotless 설정을 읽고 daemon을 시작합니다.
2. 설정이 바뀌지 않았고 이미 등록된 대상 파일이면, 이후 요청은 Gradle 없이 바로 daemon에서 처리합니다.
3. 설정이 바뀌었거나, 대상 목록에 없는 파일을 요청했거나, daemon이 실행 중이 아니면 Gradle로 daemon을 시작하거나 갱신합니다.

이 문서에서 쓰는 용어는 다음과 같습니다.

- **엔진**: Spotless Bridge 플러그인 JAR입니다. daemon과 hook이 Gradle 밖에서 실행할 수 있도록 `.gradle/spotless-bridge/`에
  복사해 둡니다. 버전은 적용한 플러그인 버전과 같습니다.
- **daemon**: Gradle 루트마다 하나씩 실행되는 백그라운드 프로세스입니다. IntelliJ, Gradle daemon과는 별개라서
  IDE를 닫아도 hook 요청을 계속 처리합니다.
- **worker**: daemon이 실행하는 프로세스로, 프로젝트의 Spotless 포매터를 불러와 메모리에 유지합니다.

Gradle 루트마다 daemon이 따로 있으므로 프로젝트마다 자체 포매터 버전과 규칙을 유지합니다.
daemon은 30분 동안 요청이 없으면 종료되고, 다음 요청 때 다시 시작됩니다.

## Gradle 태스크

`./gradlew tasks`에서는 **SpotlessBridge tasks** 아래에 표시됩니다.

| 명령 | 동작 |
| --- | --- |
| `./gradlew spotlessBridgeApply` | Spotless Java 대상 파일 전체를 포맷합니다. 결과는 `spotlessApply`와 같습니다 |
| `./gradlew spotlessBridgeApply -PspotlessBridgeFiles=src/main/java/A.java,src/main/java/B.java` | 지정한 파일만 포맷합니다. Spotless 대상이 아닌 파일은 건너뜁니다 |
| `./gradlew spotlessBridgeApply -PspotlessBridgeFiles=changed-files.txt` | UTF-8 파일에 한 줄씩 적은 파일을 포맷합니다 |
| `./gradlew spotlessBridgeStart` | daemon을 시작하거나 Spotless 설정을 다시 불러옵니다. 다른 태스크가 필요할 때 자동으로 실행합니다 |
| `./gradlew spotlessBridgeStatus` | daemon PID를 출력하며, 실행 중인 daemon이 없으면 `-1`을 출력합니다 |
| `./gradlew spotlessBridgeStop` | daemon과 worker를 종료합니다 |

경로는 Gradle 루트 기준 상대 경로나 절대 경로를 씁니다. 이 태스크들은 실행할 때마다 Gradle을 평가하므로,
AI hook에는 런처를 사용하세요.

## AI hook 자세히 보기

- 인자는 각각 포맷할 파일이며, Gradle 루트 기준 상대 경로와 절대 경로 모두 쓸 수 있습니다.
  런처는 자기 위치로 Gradle 루트를 찾으므로 어느 디렉터리에서 실행해도 됩니다.
- 런처는 프로젝트에서 Gradle이 한 번 실행된 뒤에 생깁니다. 막 clone한 프로젝트라면
  아무 Gradle 명령이나 한 번 실행하거나 IntelliJ로 프로젝트를 여세요.
- 삭제된 파일은 빼고 실제로 있는 파일만 넘기세요. 파일 인자가 없으면 아무것도 하지 않습니다.
- 오류는 `Spotless Bridge: file not found: <경로>`처럼 한 줄로 알리고 0이 아닌 종료 코드를 반환합니다.
  이때는 hook을 실패로 처리하세요.
- 처음 실행할 때는 Gradle로 엔진을 준비합니다. 이후에는 엔진의 Java 클라이언트를 직접 실행하므로,
  설정이 바뀌지 않았다면 등록된 대상 파일을 Gradle 없이 포맷합니다.
- IntelliJ는 설치되어 있지 않거나 실행 중이 아니어도 됩니다.
- `tools/spotless-bridge/ai-hook.py`를 쓰던 0.1.0에서 올라왔다면, hook이 런처를 실행하도록 바꾸고
  `tools/spotless-bridge/`를 삭제하세요.

포맷한 뒤에도 `spotlessCheck`, 컴파일, 관련 테스트 같은 프로젝트의 기존 검증은 그대로 실행하세요.
OpenRewrite를 함께 쓴다면 OpenRewrite를 먼저 실행해, 수정된 코드에 Bridge 포맷이 적용되도록 하세요.

## IntelliJ IDEA (미리보기)

IntelliJ 플러그인은 **Reformat Code**를 실행하거나 Java 파일을 저장할 때 Spotless 규칙을 적용합니다.
AI hook과 같은 daemon을 사용합니다. 아직 JetBrains Marketplace에 없으므로, 그전까지는
[소스에서 빌드해 설치](docs/install-from-source.ko.md#2-intellij-플러그인-빌드와-설치)하세요.
AI hook과 Gradle 태스크는 IntelliJ 플러그인 없이도 사용할 수 있습니다.

IDE 호환 대상은 **2025.3~2026.2**입니다. 2025.3으로 ZIP 하나를 빌드하고, 각 계열의 첫 버전·고정한
최신 패치 총 여섯 빌드에서 Plugin Verifier로 검사합니다.
[정확한 버전과 검증 범위](docs/install-from-source.ko.md#게시-없이-동일-zip으로-여러-ide-검증)를 참고하세요.
Safe Mode에서는 IntelliJ에서 프로젝트를 신뢰하기 전까지 포맷을 실행하지 않습니다.

설치한 뒤에는 다음과 같이 설정합니다.

1. **Settings → Tools → Spotless Bridge**에서 플러그인을 켭니다.
   포매터 요구 사항을 만족하는 JDK 17 이상 경로를 지정하고, hook과 같은 JDK를 사용하세요.
2. 필요하면 Gradle 루트를 지정합니다. AI hook과 같은 루트를 사용하세요.
3. Java 소스 파일에서 **Reformat Code**를 실행하면 Spotless 규칙으로 포맷됩니다.
4. 저장할 때 자동으로 포맷하려면 **Tools → Actions on Save → Reformat code**에서 **Java / Whole file**을 선택합니다.
   포맷 결과가 다시 바뀌지 않도록, 겹치는 Java 포매터와 Optimize imports·Rearrange code·Code cleanup 저장 동작은 끄세요.

플러그인은 IntelliJ가 소스로 인식하는, 쓰기 가능한 로컬 Java 파일만 처리합니다. 선택 영역 포맷은 지원하지 않습니다.
IntelliJ에는 LF 줄바꿈 텍스트를 반환하고, hook으로 저장하는 파일에는 프로젝트 Spotless의 인코딩·줄바꿈 설정을 적용합니다.

## 요구 사항과 호환성

| 도구 | 최소 버전 | 허용 범위 |
| --- | --- | --- |
| Gradle | 8.14.3 | 8.14.3 이상, 10.0 미만 |
| Spotless Gradle 플러그인 | 8.0.0 | 8.x |
| Gradle 실행 JDK | 17 | 17 이상이며, 사용하는 Gradle과 포매터가 지원하는 JDK |

허용 범위를 벗어나면 포맷하기 전에 중단하고, 감지한 버전과 허용 범위를 오류로 알려 줍니다.
범위 안이지만 [검증한 조합](#검증한-조합)에 없는 버전은 경고를 표시하고 실행합니다.

- JDK 기준은 프로젝트의 Java 소스나 컴파일 toolchain이 아니라 **Gradle을 실행하는 JVM**입니다.
  터미널과 hook에서는 `JAVA_HOME`을, IntelliJ에서는 **Gradle JVM**을 확인하고,
  `org.gradle.java.home`이나 daemon JVM criteria를 지정했다면 그 설정도 확인하세요.
- 포매터가 Bridge보다 높은 JDK를 요구할 수 있습니다. 예를 들어 Google Java Format 1.30.0은 JDK 21이 필요합니다.
  Bridge는 프로젝트가 선택한 포매터 버전을 낮추지 않고 호환 오류를 그대로 알려 줍니다.
- Bridge는 **Gradle 프로젝트의 Java 파일을 전체 파일 단위로** 포맷합니다. Maven 지원은 준비 중이며, 다른 언어는 지원하지 않습니다.
- CI는 Linux와 macOS에서 실행합니다. Windows 런처도 포함되어 있지만 아직 검증하지 않았습니다.
- Gradle configuration cache, isolated projects, Spotless `ratchetFrom`, 직렬화할 수 없는 포맷 단계는 지원하지 않습니다.
  빌드에서 configuration cache를 켜 두었다면 Bridge 태스크는 `--no-configuration-cache`로 실행하세요.
- Bridge는 Spotless 내부 API를 사용하므로, Spotless를 업그레이드하면 Bridge도 함께 업데이트해야 할 수 있습니다.
- 신뢰하는 프로젝트에서만 사용하세요. 일반 Gradle 빌드처럼 daemon도 프로젝트의 빌드 설정과 포매터 코드를 실행합니다.
  상태 디렉터리는 현재 사용자만 접근할 수 있고, 요청은 daemon마다 발급한 토큰으로 인증합니다.

### 검증한 조합

Bridge 0.1.1은 다음 조합으로 검증했습니다.

| JDK | Gradle | Spotless 검증 범위 |
| --- | --- | --- |
| 17 | 8.14.3 | 8.10.2 전체 회귀 검사, 8.0.0 버전 전환·포맷 결과 일치 검사 |
| 17 | 8.14.6 | 동일한 검사 |
| 17 | 9.0.0 | 동일한 검사 |
| 17 | 9.8.1 | 동일한 검사 |
| 21 | 8.14.3 | 동일한 검사 |
| 21 | 8.14.6 | 동일한 검사 |
| 21 | 9.0.0 | 동일한 검사 |
| 21 | 9.8.1 | 동일한 검사 |
| 25 | 9.8.1 | 동일한 검사 |

검사 항목은 Groovy/Kotlin DSL, 일반 Spotless와의 결과 일치, Palantir·Google·Eclipse 포매터, 신규·제외 파일,
설정 갱신, 공용 daemon 재사용, 동시 요청, IDE 없이 실행하는 hook입니다. CI는 모든 조합을 Ubuntu에서 실행하고,
그중 두 조합은 macOS에서도 실행합니다. JDK 25와 Gradle 8.x 또는 9.0.0의 조합은 검증하지 않았습니다.

## 설정 변경

- 빌드 스크립트, Gradle 설정, wrapper 파일, `.editorconfig` 같은 포맷 설정 파일의 변경은 자동으로 감지합니다.
  환경 변수나 감지 대상 밖의 입력을 바꿨다면 `spotlessBridgeStart`를 실행해 다시 불러오세요.
  IntelliJ에서 추가한 감지 경로는 해당 IDE 클라이언트에만 적용됩니다.
- IDE와 hook 요청에는 같은 Gradle 속성과 환경을 사용하세요. 같은 Gradle 루트의 클라이언트는 가장 최근에 불러온 설정을 함께 씁니다.
- 제외된 파일은 수정하지 않습니다. 대상 목록에 없는 파일은 Gradle 평가를 거치므로, 제외된 파일을 반복해서 넘기면 매번 다시 평가합니다.
  포맷할 Java 파일만 넘기고, 생성 코드를 포맷하려면 소스 생성 작업을 먼저 실행하세요.

## 문제 해결

평소에는 daemon을 직접 관리할 필요가 없습니다. 상태를 확인하려면 Gradle 루트에서 `./gradlew spotlessBridgeStatus`를 실행하세요.
`Spotless Bridge pid=<숫자>`를 출력하며, `-1`이면 실행 중인 daemon이 없다는 뜻입니다.
IntelliJ와 hook이 같은 daemon을 쓰는지 확인하려면 양쪽에서 파일을 포맷한 뒤 PID가 그대로인지 보면 됩니다.

실행 정보와 로그는 프로젝트의 `.gradle/spotless-bridge/`에 저장되며, 이 디렉터리는 Git에 포함하지 마세요.
요청이 실패하면 이 디렉터리의 `daemon.log`, `worker.log`, `gradle-start-*.log`를 확인하세요.
디렉터리를 삭제하려면 먼저 `./gradlew spotlessBridgeStop`으로 daemon을 종료하세요.

IDE와 hook이 동시에 요청하면 순서대로 처리합니다. 설정이 바뀌면 포매터 worker를 다시 불러오고,
엔진이나 JDK가 바뀌면 daemon까지 교체합니다.

## 변경 내역

버전별 변경 사항과 업그레이드 방법은 [CHANGELOG.md](CHANGELOG.md)를 참고하세요.

## 기여하기

소스에서 Bridge를 빌드하거나, 테스트를 실행하거나, 배포 전 변경 사항을 써 보려면 [소스에서 설치하기](docs/install-from-source.ko.md)를 참고하세요.
구현과 배포 절차는 [유지보수 문서](docs/maintenance.md)에 정리되어 있습니다.

## 라이선스

[MIT](LICENSE). 소비 프로젝트가 선택하는 Spotless와 포매터 라이브러리에는 각각의 라이선스가 적용됩니다.
