# Pleos Android app

Pleos Connect용 Android 앱이다. 현재 실행 화면은 개발 환경·연결 점검이다. 뉴스 학습의 순수 정책과 Room 기반을 추가했으며 제품 화면·미디어/음성·서버 연결은 아직 미완료다.

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
| `:core:domain` | 연결 진단, 고정 콘텐츠/설정, 듣기·질문·도움·대기·입력 lease/직렬 상태 정책 |
| `:core:data` | Supabase 진단/Preferences와 owner·epoch별 Room record/outbox·전환 journal |
| `:core:pleos` | 기존 SDK 진단과 API34 public car 읽기 후보. Media3는 의존성만 추가, service는 미연결 |

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

## S2 기반 구현과 검증 범위

- JVM21건: 8개 후속 조합·대기/입력 경합·owner·제목/복귀·도움·빈 인식/저장 실패를 검증했다.
- Pleos API34 AVD Room3건: DB 재열기·불변 제출·owner/epoch 거절·로그아웃 전환 journal/격리를 확인했다. SDK/서버 logout 성공은 아니다.
- public car monitor1건: 권한 없을 때 UNKNOWN/닫힘, 테스트 APK(target28)의 허용된 CAR_SPEED 권한 뒤 P/UXR/component policy 읽기를 확인했다. 제품 target35 입력·주행·실차·Suspend 검증은 아니다. `CarAccessMonitor`는 API34 이상에서만 생성한다.
- debug/release/lint는 통과했다. 외부 권한·실제 Gleo/직접 STT/TTS·Media3 service·12개 제품 화면·Auth/API·다중 기기 동기화는 미완료다.

```sh
source scripts/dev-env.sh
./gradlew :core:domain:test :core:data:connectedDebugAndroidTest :core:pleos:connectedDebugAndroidTest
```

Room의 새 provisional owner·기기 저장을 서버 로그인/저장 완료로 표시하지 않는다. 직접 입력은 현재 gate와 lease/final 문장을 모두 확인한 뒤 원자 로컬 저장을 거쳐야 한다. permission callback·P 복귀·재구성은 자동 녹음을 시작하지 않는다. 학습 DB backup은 기존 앱 `allowBackup=false` 정책을 따른다. 세션 비밀값을 이 DB에 넣지 않는다.

이 변경은 화면·문구·화면 전환을 바꾸지 않아 기존 Sites 배포 대상에서 제외했다. 현재 구현은 전체 뉴스 학습 기능 완료가 아니다. 전체 기능 이슈는 열어 두며 PR은 독립 기반에 한정한다.
