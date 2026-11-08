# PleosChallengeApp

운전자용 영어 뉴스 브리핑 앱의 Android Studio 프로젝트다. 현재 Kotlin 시작 화면이 있다. [제품 방향](../../README.md)

## 현재 설정

- Kotlin / Android Gradle Plugin 8.8.2
- Gradle Wrapper 8.10.2
- `minSdk 28`, `compileSdk 35`, `targetSdk 35`
- JDK 17 bytecode 호환 설정
- Pleos 전용 Maven 저장소 등록
- Pleos SDK 의존성과 차량 권한은 프로젝트별 사용 상태를 확인한 후 추가
- 로컬 개발 런타임: Android Studio JBR 21, Android SDK API 34/35
- Pleos Connect 2.1.0 API 34 ARM64 system image 설치 완료
- AVD `Pleos_Connect_v2_1`: Pleos 차량 프로필, RAM 4GB, Cold Boot
- 2026년 10월 22일 에뮬레이터 첫 부팅 시도는 여유 공간 검사에서 중단됐다. [로컬 설정 기록](../docs/environment.md)

## 실행

Android Studio에서 이 디렉터리를 연다. 터미널에서는 다음 명령으로 debug APK를 만든다.

```sh
source scripts/dev-env.sh
./gradlew assembleDebug
```

빌드 산출물: `app/build/outputs/apk/debug/app-debug.apk`.

에뮬레이터를 시작하려면 프로젝트 루트에서 아래 스크립트를 실행한다. 매번 스냅샷 없이 Cold Boot 한다.

```sh
scripts/start-pleos-emulator.sh
```

첫 부팅 전에는 디스크 여유 공간이 12GB 이상인지 확인한다.

Android Studio에서는 Device Manager에서 `Pleos_Connect_v2_1`을 선택해 실행할 수 있다. Android SDK와 AVD는 이 Mac의 사용자 계정에 설치되어 있으며, 프로젝트 복제 시 다른 개발자는 Pleos 저장소에 등록하고 라이선스에 동의한 뒤 별도로 설치해야 한다.

## 주의

- `local.properties`는 이 Mac의 Android SDK 경로를 가리키며 Git에 올리지 않는다.
- `applicationId`는 시작용 값(`com.example.pleosapp`)이므로 출시 전에 고유한 값으로 교체한다.
- Pleos SDK를 연동하기 전 Playground `Workspace → My Project`에서 프로젝트별 지원과 권한을 확인한다.
- TTS/STT Client Secret 등 비밀 값은 소스나 저장소에 넣지 않는다.
