# Pleos Android app

Pleos Connect용 Android 앱의 초기 개발환경이다. 디버그 앱은 뉴스 듣기 디자인 시연으로 시작하며, 우측 상단 설정 → 연결 점검에서 기존 개발 환경 화면을 연다. 릴리스 앱은 기존 개발 환경 화면으로 시작한다.

## 환경과 실행

- Android SDK Platform 36, JDK 17 이상, Gradle Wrapper 8.13
- 앱 최소 API 28, 대상 API 35
- Pleos Connect 2.1.0 API 34 ARM64 AVD 이름 `Pleos_Connect_v2_1`

Android Studio에서 저장소를 연다. 터미널에서 빌드할 때는 아래 명령을 실행한다.

```sh
source scripts/dev-env.sh
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :core:pleos:testDebugUnitTest
scripts/start-pleos-emulator.sh
```

`app/build/outputs/apk/debug/app-debug.apk`를 Pleos AVD에 설치한다.

```sh
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n io.github.driveenglishlab.pleos/.DesignPreviewActivity
```

AVD의 현재 사용자는 `adb shell am get-current-user`로 확인한다. Mac에서 자동 렌더러가 소프트웨어 모드를 선택하고 기본 차량 화면이 반복 종료되면, 데이터를 지우지 않고 AVD를 종료한 뒤 `scripts/start-pleos-emulator.sh -gpu host`로 다시 실행한다.

## 디자인 시연

창작 기사 세 편으로 영어 샘플 재생, 문장 강조·자동 스크롤, 한국어 번역, 기사 선택, 정보·출처, 질문·예시 답변·재시도·피드백·메모리 기록·설정 적용을 확인한다. 얇은 제목 행과 하단 중앙 재생 조작 사이에서 대본만 스크롤한다. 넓은 앱 창에서는 다음 기사와 말하기 연습을 오른쪽에 둔다. Asta Sans와 블루 테마를 사용하며 시스템 다크 모드에 대응한다.

음성 입력·Gleo·사용자 DB를 연결한 학습 기능은 아니다. 시연 리소스와 Activity는 `src/debug/`에만 포함된다. `core:pleos`의 Media3 서비스가 재생을 소유하며 화면은 MediaController로 연결한다. 실제 P·속도0·제한 해제일 때 대본과 연습을 열고, D/R/N·상태 미확인에서는 큰 미디어 조작만 표시한다. Activity가 가려져도 재생을 유지하며 STOP·영구 포커스 상실·task 제거는 정지한다. 릴리스 카탈로그는 공급자 연결 전까지 비어 있다. [미디어 계약과 검증](docs/driving-media/README.md). [범위와 AVD 검증](docs/design-preview/README.md)

## 모듈

| 모듈 | 역할 |
| --- | --- |
| `:app` | Compose, Navigation, ViewModel, Hilt 앱 진입점 |
| `:core:domain` | 연결 진단 상태와 저장소 계약 |
| `:core:data` | Supabase Kotlin PostgREST 클라이언트, Ktor/OkHttp, Preferences DataStore |
| `:core:pleos` | Pleos SDK·진단, 실제 차량 상태, 공통 Media3 재생·카탈로그 계약 |

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
