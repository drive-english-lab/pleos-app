# Pleos Android app

Pleos Connect용 Android 앱이다. 현재 실행 화면은 개발 환경·연결 점검이다. schema5 계약, Room 보존 이행과 청취·기사 질문·연습의 저장/API port를 구현했다. 실제 익명 Auth/프로필·기본 설정 서버 연결을 추가했으며 제품 화면·미디어/음성과 나머지 업무 서버 연결은 계속 구현 중이다.

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
| `:core:domain` | 고정 콘텐츠/설정, canonical 질문·언어 증거·노출 타입, 듣기·질문·도움·대기·입력 lease/직렬 상태 정책 |
| `:core:data` | schema5 typed API, 청취·QA·연습 repository, owner·epoch별 Room record/outbox·ticket/관찰·전환 journal |
| `:core:pleos` | API34 public car/window source, 기본 비활성 Gleo 전송·미디어 command port. Media3 service는 미연결 |

## Supabase

Android Studio가 생성한 `local.properties`에 두 설정을 추가한다. CI와 기본 빌드에서는 생략할 수 있다.

```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

승인된 공개 콘텐츠는 Supabase 직접 읽기, 개인 기록·추론은 사용자 세션을 검증하는 별도 LearningApi 경로다. 현재 진단은 SDK로 프로젝트 게이트웨이에 응답을 요청하고 DataStore에 마지막 점검 시각을 저장한다. 개인 Auth 세션 생성/복원과 실제 업무 backend는 미연결이다. 사용자 세션 port에 서버용 secret/model key를 넣지 않는다.

## Pleos SDK와 Gleo

- Vehicle 점검은 차량 서비스의 Door 읽기 capability를 확인한다. NaviHelper는 초기 연결 콜백, FusedLocation은 `isSupported()`로 확인한다.
- Gleo Interface에는 디버그 빌드의 `assets/schema.json`과 `GleoProbeReceiver`가 연결돼 있다. 설정 점검 액션을 받으면 고정 콜백을 브로드캐스트한다.

```sh
adb shell am broadcast -a DRIVE_ENGLISH_CHECK_SETUP -p io.github.driveenglishlab.pleos
adb logcat -d -s GleoProbe:I
```

ADB 확인과 Gleo 음성 호출 검증은 별도다. 실제 Gleo 통합에는 Pleos의 App Schema 검토가 필요하다. FusedLocation 권한과 지원 여부는 Playground 프로젝트와 대상 기기에서 각각 확인한다. 앱 ID나 버전을 바꿀 때는 디버그 스키마의 `packageName`과 `version`도 맞춘다.

[기여 규칙](CONTRIBUTING.md)

## schema5 기반과 검증 범위

- JVM48건: 8개 후속 조합·실제 종료가 필요한 대기·입력/owner 경합·제목/복귀·영어 증거·준비/제공 도움·처리 예산을 검증했다.
- Pleos API34 AVD data29건: Room1→2 보존 이행·DB 재열기·불변 제출·owner/epoch·로그아웃 journal, 계약 정상80/반례102 vector, scoped API·청취/QA/연습 저장·복귀·늦은 결과·명시 GET/재시도를 확인했다. synthetic HTTP이며 실제 Auth/업무 서버 성공은 아니다.
- Gleo port3건: callback을 가로채 객체 배열/승인 route를 검증했고, 미확인 schema/발화 문장이 미디어 command가 되지 않음을 확인했다. 실제 Gleo로 전송하거나 실제 출력/원언어를 검증한 테스트가 아니다.
- public car monitor1건: 권한 없을 때 UNKNOWN/닫힘, 테스트 APK(target28)의 허용된 CAR_SPEED 권한 뒤 P/UXR/component policy 읽기를 확인했다. 제품 target35 입력·주행·실차·Suspend 검증은 아니다. `CarAccessMonitor`는 API34 이상에서만 생성한다.
- debug/release/lint(error0, warning26)가 통과했다. 외부 권한·실제 Gleo/직접 STT/TTS·Media3 service·12개 제품 화면·Auth/업무 API·다중 기기 동기화는 미완료다.

```sh
source scripts/dev-env.sh
python3 scripts/sync-learning-contracts.py --check
./gradlew :core:domain:test
./gradlew :core:data:connectedDebugAndroidTest
./gradlew :core:pleos:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.driveenglishlab.pleos.platform.GleoTransportTest,io.github.driveenglishlab.pleos.platform.GleoMediaGatewayTest
```

Room의 새 provisional owner·기기 저장을 서버 로그인/저장 완료로 표시하지 않는다. 직접 입력은 현재 gate와 lease/final 문장을 모두 확인한 뒤 원자 로컬 저장을 거쳐야 한다. permission callback·P 복귀·재구성은 자동 녹음을 시작하지 않는다. 학습 DB backup은 기존 앱 `allowBackup=false` 정책을 따른다. 세션 비밀값을 이 DB에 넣지 않는다.

Room1의 schema3 원본·outbox·owner·journal은 migration2에서 보존한다. legacy payload는 v5 전송과 구분하고, 근거 없는 노출/영어 증거를 verified로 만들지 않는다. 계약 JSON3개와 manifest에서105개 wire type/21개 보호 operation을 생성하며 CI의 `--check`로 차이를 검출한다. GET/receipt는 출력 명령이 아니다. 준비한 도움은 실제 관찰과 구분하고 기본 Gleo capability/feedback presentation verifier는 비활성이다.

이 변경은 화면·문구·화면 전환을 바꾸지 않아 기존 Sites 배포 대상에서 제외했다. 현재 구현은 전체 뉴스 학습 기능 완료가 아니다. 전체 기능 이슈는 열어 두며 PR은 독립 기반에 한정한다.

## S3/S4 독립 계약과 Room3

기존 `learning-contracts`3개/105타입·21보호 operation을 유지하고, 별도 `feature-contracts`7개에서 learning-progress1/ai-learning1의77타입·12개 typed API를 생성한다. 공통 오류·필수/null/버전/enum·owner/epoch·입력/응답 변경을 검사하며 API 성공은 출력 명령이 아니다.

```sh
python3 scripts/sync-feature-contracts.py --check
```

Room2→3 명시 migration은8개 feature cache/journal을 더하고 기존 payload/hash·답/pending·노출/중지 의도를 보존한다. 당일/난이도 설정과 전용 journal은 epoch 독립이고 카드/시각·추천/선택 sidecar cache와 명령은 학습 epoch별이다. 원래 intent의 clock/boot 관찰은 JSON 그대로 보존하며 wire/hash를 재작성하지 않는다. 실제 없는 날짜/순서/coverage는 만들지 않는다.

최종 AVD data40개(기존29+계약8+이행/수명3), domain48개, debug/release/lint(error0/warning26)를 확인했다. Gradle connected runner의 장치 감시 대기 뒤 최신 APK를 직접 설치하고 단일 `am instrument`로40개를 검증했다. 실제 Auth/Gleo/음성·제품 화면 연결 완료는 아니다. FeatureLifecycle의 삭제/owner 전환 hook과 ProfileSyncWorker의 전후 guard는 실제 Auth/merge/delete/logout composition에서 이어 연결한다. 화면이 바뀌지 않아 이번 변경의 Sites 배포는 하지 않았다.

## 익명 Auth·프로필 실제 연결 · 2026-11-13

`ProfileRepository`가 Supabase Auth SDK3.6.0의 익명 생성/복원·서버 bootstrap/read/preferences를 연결한다. 기존 버전을 유지하고 Auth 모듈만 명시 의존성으로 추가했다. `AuthSessionStore`는 AndroidKeyStore AES-GCM과 noBackup 디렉터리를 사용하며 SDK 로그를 비활성화했다. 생성 요청 전 Room/보호 파일의 fence를 영속화해 복원 실패/응답 유실을 새로운 guest로 바꾸지 않는다. 첫 provisional owner/outbox의 ID와 불변 bytes를 보존한다. 다른 server profile은 명시 전환 필요 상태로 남기고 기존 기록을 옮겨 붙이지 않는다.

설정 command는 ACK까지 원래 operation/payload를 유지한다. 확인된 원격 privacy epoch가 증가하면 옛 학습/학습 outbox를 제거하고 독립 선호·미완료 설정 command는 유지한다. stale owner/session 결과는 거절한다. 프로필 repository의 실제 연결과 앱 제품 화면의 wiring은 구분한다. 이 변경으로 개발 점검 화면을 제품 화면으로 교체하지 않았다.

검증: domain JVM48개, AVD 데이터48개 통과/실제 연동 전용1개 기본 skip(runner49개), 별도 opt-in 실제 SDK/Auth/API/DB1개 통과. 실제 테스트 사용자와 owned DB 행은 host runner가 정리한다. debug/release/lint(error0/warning27) 통과. 추가 warning1개는 고정 Auth SDK의 새 버전 안내다. 앱의 서버용/모델용 비밀값은0개다.

실제 테스트는 서버 저장소의 `scripts/verify_android_profile_runtime.py --live`로 실행한다. test APK에만 localhost HTTP 허용과 INTERNET를 두고 adb reverse로 localhost8008의 테스트 서버에 연결한다. 앱 release 네트워크 정책은 변경하지 않았다. 실제 Gleo·차량·제품 화면/이력 삭제/계정 병합은 후속 작업이다. visible diff가 없어 Sites 갱신은 제외했다.
