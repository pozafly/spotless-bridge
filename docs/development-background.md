# Spotless Bridge의 목적과 동작

> 이 문서는 공개 배포 전 개발판 0.4.0까지의 제작 배경입니다. 첫 공개 배포용 버전은 0.1.0으로 정리했습니다.
> 현재 구현에서는 Gradle 플러그인이 엔진을 배포하고 IntelliJ와 hook이 프로젝트별 daemon을 공유합니다.
> 현재 동작은 [README](../README.md)를 따릅니다.

## 목적

프로젝트의 Spotless 규칙을 IntelliJ 저장에서도 사용하면서, 반복 요청의 Gradle 실행과
포매터 초기화 비용을 줄이려고 만든 도구입니다. 프로젝트가 버전과 규칙을 정하고,
Spotless Bridge는 그 설정으로 포맷을 반복 실행합니다.

Formo 0.3.0은 Spotless 버전을 내부 Gradle 플러그인에서 고정하고 `formo {}` 설정과 별칭 태스크를
제공했습니다. 0.4.0부터 이 계층을 제거했습니다. 현재 제품 이름은 Spotless Bridge입니다.
OpenRewrite 연동과 별도 CLI는 현재 기능에 포함하지 않습니다.

## 실행 흐름

```text
IntelliJ 저장 요청
  → GradleFormatter: 프로젝트 설정의 변경 여부 확인
  → 처음이거나 변경됐다면 프로젝트 wrapper로 export.gradle 실행
  → 프로젝트 Spotless의 설정·클래스 경로·대상 파일 내보내기
  → WorkerClient: 상주 worker에 파일 경로와 문서 내용 전달
  → Worker: 프로젝트 포매터 실행 후 결과 반환
  → IntelliJ: 문서가 여전히 최신이면 결과 반영
```

반복 요청에서는 Gradle 설정 평가·worker 시작·포매터 초기화를 생략합니다.
소스 파싱, 실제 포맷 계산, 설정 변경 감지와 프로세스 통신은 매번 필요합니다.
첫 요청 시간은 프로젝트 구성과 의존성 캐시 상태에 따라 달라집니다.

## 프로젝트 라이브러리를 가져오는 방법

버전 문자열을 빌드 파일에서 찾거나 같은 버전의 JAR를 별도로 설치하지 않습니다.
프로젝트 wrapper가 Gradle 설정을 평가한 후, `spotlessJava`의 클래스 로더에서 실제 라이브러리
경로를 얻고 포매터 객체를 직렬화합니다. worker는 이 경로로 클래스 로더를 만들어 객체를 복원합니다.
따라서 상위 빌드에서 적용한 플러그인이나 Kotlin DSL도 평가된 결과를 기준으로 처리합니다.

Gradle과 worker의 연결에는 Spotless 내부 API를 사용합니다. 프로젝트 버전 사용과 모든 버전 호환은
별개의 문제이며, 지원 조합은 실제 Gradle 결과와 비교해 검증해야 합니다.

## 프로세스와 문서 수명

Gradle 루트별 worker를 유지하고 표준 입출력의 이진 프로토콜(`SLD1`)로 통신합니다.
요청은 파일 경로와 UTF-8 문서 내용, 응답은 상태와 결과 텍스트입니다. HTTP 서버는 필요하지 않습니다.
하나의 worker는 요청을 순서대로 처리합니다. 실패·시간 초과 후 다음 요청에서 재시작하며,
프로젝트 종료·사용 중지 시 worker도 종료합니다.

설정이 변경되면 다음 요청에서 이전 worker를 종료하고 다시 준비합니다.
포맷 중 빌드 설정이나 문서가 바뀌었다면 오래된 결과를 적용하지 않습니다.
worker는 결과 텍스트만 반환하고 문서 반영·파일 저장은 IntelliJ가 담당합니다.

## 코드 읽는 순서

| 파일 | 역할 |
| --- | --- |
| [JavaFormattingService.java](../src/idea/java/io/github/pozafly/spotlessbridge/idea/JavaFormattingService.java) | 저장·포맷 요청과 문서 결과 반영 |
| [ProjectEngine.java](../src/idea/java/io/github/pozafly/spotlessbridge/idea/ProjectEngine.java) | Gradle 루트별 엔진과 종료 처리 |
| [GradleFormatter.java](../src/main/java/io/github/pozafly/spotlessbridge/GradleFormatter.java) | 설정 변경 감지·Gradle 실행·재로딩 |
| [export.gradle](../src/main/resources/export.gradle) | 프로젝트 Spotless 설정과 라이브러리 경로 추출 |
| [WorkerClient.java](../src/main/java/io/github/pozafly/spotlessbridge/WorkerClient.java) | 프로세스·통신·시간제한 |
| [Worker.java](../src/main/java/io/github/pozafly/spotlessbridge/Worker.java) | 프로젝트 라이브러리 로드와 포맷 실행 |
| [FormatterSelfTest.java](../src/test/java/io/github/pozafly/spotlessbridge/FormatterSelfTest.java) | Spotless 버전·설정 변경과 Gradle 결과 비교 |
| [ProcessSelfTest.java](../src/test/java/io/github/pozafly/spotlessbridge/ProcessSelfTest.java) | 프로세스 재사용·복구·동시 요청·종료 검증 |

지원 범위, 설정 감지 대상과 제한은 [README](../README.md), 버전별 검증 책임은
[유지보수 문서](maintenance.md)에 정리했습니다. 과거 Formo 벤치마크 수치를 현재 버전의 측정값으로 사용하지 않습니다.
