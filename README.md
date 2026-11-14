# Pleos Android app

Pleos Connect용 Android 앱이다. 기본 진입은 실제 프로필·catalog·대본·단일 기사 미디어 port에 연결한 제품 화면이다. 차량 전원·owner·입력 권한을 확인하지 못하면 준비/제한 상태를 표시한다. 연결 점검은 별도 destination으로 보존한다. schema5·Room 기반은 구현했으며 기사 질문·연습의 제품 결합과 외부 음성 승인은 계속 진행 중이다.

## 미디어 연결 진행 · 2026-11-14

`NewsMediaService`에 단일 기사 MediaLibraryService/legacy browse·MediaSession, 명시 재생/일시정지/탐색/STOP과 오디오 focus를 구현했다. Media3가 실제로 진행한 구간만 기록하고, 탐색·버퍼링·긴 scheduler 공백으로 생긴 구간은 완청으로 보충하지 않는다. 새 HTTP/range 요청은 같은 고정 자산의 접근 주소만 갱신한다. EOS가 다음 기사나 Gleo 대기 타이머를 만들지 않는다.

`NewsPlaybackRepository`와 `MediaPlaybackJournal`은 개인 세션·원래 콘텐츠/자산/run을 고정하며 Room에 관찰 sequence와 마지막 위치를 원자 저장한다. 관찰이 서버 체크포인트 버전을 바꿀 수 있어, 전환 시 관찰 flush→서버 CAS 확인→위치 저장 순서를 사용한다. 재시도는 저장한 원본 run/형식 변경 요청을 보낸다.

제품 Gleo3함수는 `app/src/main/assets/schema.json`에 있다. 디버그 점검 스키마는 `assets/diagnostics/schema.json`으로 옮겨 제품 스키마를 덮지 않는다. 제품 receiver는 실제 sender identity·프로젝트 adapter/인자 형태·콜백 승인이 없으면 실행하지 않는다. 추가 Pleos 특권 권한을 추측해 선언하지 않았다.

`ProductSessionHost`/`ProductViewModel`은 하나의 `SessionCoordinator`를 사용한다. S01~S04/S10/S12와 설정 초안·기기 theme, 대응 대본 언어별 scroll, 기사별 MediaBrowser 조작을 연결했다. 완료 응답은 시작 owner/work/operation으로 검증하며 늦은 응답이 이동한 화면을 다시 열지 않는다. 형식 변경 결과 재확인은 같은 원본 operation을 재전송한다. 전환 시 FREEZE가 media writer를 drain한 최종 커서를 저장하며 사용자 재생 의도와 내부 정지를 구분한다.

실제 power adapter는 UNKNOWN을 유지한다. Activity resume/component policy나 테스트 fixture로 ALLOWED를 만들지 않으므로 실제 앱에서 대기 화면이 나오는 것이 현재 경계다. 실제 Gleo 제품 승인·주행 음성·물리 미디어 버튼·실차 검증은 남아 있어 T021 전체를 완료로 표시하지 않는다. 기존 UI PR7/004 워크트리는 병합하지 않았다. 제품 화면의 visible diff는004 담당의 후속 갱신 대상이며 이 묶음에서 Sites 소스/배포는 변경하지 않았다.

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
| `:core:pleos` | API34 public car/window source, 기본 비활성 Gleo 전송, 단일 자산 Media3 service/legacy browse와 제품 runtime port. 실제 Gleo/전원 gate 별도 |

## Supabase

Android Studio가 생성한 `local.properties`에 두 설정을 추가한다. CI와 기본 빌드에서는 생략할 수 있다.

```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

승인된 공개 콘텐츠는 Supabase 직접 읽기, 개인 기록·추론은 사용자 세션을 검증하는 별도 LearningApi 경로다. 현재 진단은 SDK로 프로젝트 게이트웨이에 응답을 요청하고 DataStore에 마지막 점검 시각을 저장한다. 익명 Auth 세션 생성/복원과 프로필·기본 설정은 실제 검증했다. 나머지 업무 adapter와 제품 화면 결합은 구현 중이다. 사용자 세션 port에 서버용 secret/model key를 넣지 않는다.

## 제품 API 연결과 진단 진입

`local.properties`의 `LEARNING_API_ORIGIN`은 개인 LearningApi의 HTTPS origin이다. 비어 있으면 제품 연결 준비 상태를 표시하며 새 익명 사용자를 임의 생성하지 않는다. `LEARNING_API_DEBUG_ORIGIN`은 debug 전용 override다. HTTPS 또는 정확히 `localhost`/`127.0.0.1`/`10.0.2.2`의 HTTP만 허용한다. HTTP 예외는 debug network-security 파일의 해당 host에만 적용되며 release는 HTTPS만 사용한다. 경로·query·사용자 정보가 붙은 origin은 거부한다. 실제 서버·공개 자료·승인된 음원은 별도 준비가 필요하다.

`MainActivity.diagnosticsIntent(context)` 또는 `io.github.driveenglishlab.pleos.DESTINATION=diagnostics` extra로 기존 연결 점검을 연다. extra 없는 시작은 제품 진입이다. UI004의 기존 debug launcher는 이 인터페이스로 갱신해야 한다. 제품 서비스는 `io.github.driveenglishlab.pleos.platform.NewsMediaService`이며003 샘플의 `platform.media.NewsMediaService`와 다르다. 향후 통합에서 두 browse authority를 동시에 제품 원본으로 등록하지 않는다.

## 제품 화면 묶음 검증

앱의 Compose instrumentation harness는 `app/src/androidTest/java/io/github/driveenglishlab/pleos/ProductScreensTest.kt`다. renderer/typed intent fixture이며 실제 Auth·오디오·차량 전원·Gleo 성공을 뜻하지 않는다. `:app:assembleDebugAndroidTest`로 test APK를 만든다. 실제 기기 실행은 배정된 serial과 AVD 이름/user를 확인하고 `adb -s <serial> shell am instrument --user <user> -w -r io.github.driveenglishlab.pleos.test/androidx.test.runner.AndroidJUnitRunner`를 사용한다. 다른 담당의 AVD에 설치하지 않는다.

2026-11-14 기능 묶음 결과: domain55개, AVD data67개+opt-in1개 제외, platform12개, 제품 Compose4개 통과. debug/release/lint는 error0/warning37이다. 플랫폼 검증은 자체 무음 WAV와 실제 Media3/focus/browser를 사용했고, 화면 검증은 합성 renderer 상태다. 실제 뉴스→전체 US1·Gleo·차량 power/실차 성공과 구분한다. 기본 Pleos AVD를 read-only5556/user10으로 사용했으며 UI 담당5554는 변경하지 않았다.

기능 묶음을 구현한 뒤 domain/data/platform/제품 화면 검증과 debug/release/lint를 모아서 실행한다. 최신 결과와 미확인 사항은 [통합 구현 기록](../docs/news-speaking-implementation.md)의2026-11-14 절이 기준이다. 아래 개별 기반 검증은 당시 기록이다.

## Pleos SDK와 Gleo

- Vehicle 점검은 차량 서비스의 Door 읽기 capability를 확인한다. NaviHelper는 초기 연결 콜백, FusedLocation은 `isSupported()`로 확인한다.
- 제품 App Schema는 main의3함수다. `GleoProbeReceiver`와 `assets/diagnostics/schema.json`은 디버그 수동 점검 전용이며 제품 함수 승인/활성화를 대신하지 않는다. 설정 점검 액션을 받으면 고정 콜백을 브로드캐스트한다.

```sh
adb shell am broadcast -a DRIVE_ENGLISH_CHECK_SETUP -p io.github.driveenglishlab.pleos
adb logcat -d -s GleoProbe:I
```

ADB 확인과 Gleo 음성 호출 검증은 별도다. 실제 Gleo 통합에는 Pleos의 App Schema 검토가 필요하다. FusedLocation 권한과 지원 여부는 Playground 프로젝트와 대상 기기에서 각각 확인한다. 앱 ID나 버전을 바꿀 때는 main 제품 스키마의 `packageName`과 `version`도 맞춘다.

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

## 공개 기사·대본 및 private 음원 접근 · 2026-11-13

`CatalogRepository`는 publishable key의 고정 GET으로 공개 catalog/bundle/script만 읽는다. 고정 article/bundle/source snapshot·role/base script·언어·UTF-8 text hash를 검사하고, 원문 시각 unknown과 서버 게시 시각을 분리한다. 목록 없음은 빈 결과이고 철회된 고정 자료는 CONTENT_UNAVAILABLE이다. 원문/Rubric private 테이블에는 접근하지 않는다.

`AudioAccessRepository`는 고정 asset의 URI credential만 갱신하며 기사/위치/run을 선택하지 않는다. 최초 요청을 재시도할 때 같은 operation을 유지하고 긴 재생·seek/resume의 force refresh와 확정 만료 뒤에는 새 operation을 쓴다. 프로세스 내 monotonic4분 refresh hint는 출력 허용/차량 시각이 아니며 재실행으로 복원하지 않는다. 같은 owner/content의 동시 응답은 같은 원래 receipt를 공유하고 owner/session 변경 뒤 이전 URI는 반환하지 않는다. URI/hash/duration/Storage origin을 검증하고 URL의 toString은 redacted다. 서버가 권리/원본 object metadata를 재검사한다. 이미 받은 byte의 즉시 철회는 보장하지 않는다.

검증: JVM48개, AVD 데이터59개 통과/실연동 전용1개 기본 skip(runner60개), 별도 실제 SDK/Auth·공개 catalog GET·설정 API/DB1개 통과. debug/release/lint(error0/warning27), 두 contract generator check 통과. malformed URI/date·해시/고정 snapshot·캐시 격리·credential 만료/재시도/동시 요청을 확인했다. 화면/조작 변경이 없어 pleos-sites-preview 기준 Sites 갱신은 제외했다. 실제 Media3/차량/제품 화면 연결은 후속 task다.
