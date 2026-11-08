# Pleos Android app

Pleos Connect용 Android 앱의 초기 개발환경이다. 실행하면 개발 환경 화면에서 연결 점검으로 이동할 수 있다.

## 환경과 실행

- Android SDK Platform 36, JDK 17 이상, Gradle Wrapper 8.13
- 앱 최소 API 28, 대상 API 35
- Pleos Connect 2.1.0 API 34 ARM64 AVD 이름 `Pleos_Connect_v2_1`

Android Studio에서 저장소를 연다. 터미널에서 빌드할 때는 아래 명령을 실행한다.

```sh
source scripts/dev-env.sh
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
scripts/start-pleos-emulator.sh
```

`app/build/outputs/apk/debug/app-debug.apk`를 Pleos AVD에 설치한다.

## 모듈

| 모듈 | 역할 |
| --- | --- |
| `:app` | Compose, Navigation, ViewModel, Hilt 앱 진입점 |
| `:core:domain` | 연결 진단 상태와 저장소 계약 |
| `:core:data` | Supabase Kotlin PostgREST 클라이언트, Ktor/OkHttp, Preferences DataStore |
| `:core:pleos` | Pleos Vehicle·NaviHelper·FusedLocation SDK와 진단 |

## Supabase

Android Studio가 생성한 `local.properties`에 두 설정을 추가한다. CI와 기본 빌드에서는 생략할 수 있다.

```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

앱은 Supabase로 직접 연결한다. 앱의 현재 진단은 SDK를 통해 프로젝트 게이트웨이에 응답을 요청하며, DataStore에는 마지막 점검 시각을 저장한다. 앱에서 사용할 테이블은 스키마 작업 때 RLS와 명시적인 Data API 권한을 함께 설정한다. 서버용 secret key는 수집 서버 환경에 둔다.

## Pleos SDK와 Gleo

- Vehicle 점검은 차량 서비스의 Door 읽기 capability를 확인한다. NaviHelper는 초기 연결 콜백, FusedLocation은 `isSupported()`로 확인한다.
- Gleo Interface에는 디버그 빌드의 `assets/schema.json`과 `GleoProbeReceiver`가 연결돼 있다. 설정 점검 액션을 받으면 고정 콜백을 브로드캐스트한다.

```sh
adb shell am broadcast -a DRIVE_ENGLISH_CHECK_SETUP -p io.github.driveenglishlab.pleos
adb logcat -d -s GleoProbe:I
```

ADB 확인과 Gleo 음성 호출 검증은 별도다. 실제 Gleo 통합에는 Pleos의 App Schema 검토가 필요하다. FusedLocation 권한과 지원 여부는 Playground 프로젝트와 대상 기기에서 각각 확인한다. 앱 ID나 버전을 바꿀 때는 디버그 스키마의 `packageName`과 `version`도 맞춘다.

[기여 규칙](CONTRIBUTING.md)
