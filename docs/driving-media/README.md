# Driving media · 003

공통 재생 기반과 현재 debug 뉴스 화면 연결이다. issue #6 / PR #7 → develop. 별도 제품 브랜치와 001의 Auth·DB·Gleo·실제 뉴스 작업을 완료한 범위가 아니다.

## 구조와 계약

- core:pleos의 NewsMediaService가 ExoPlayer와 MediaLibrarySession 하나를 소유한다. Activity는 MediaController로 같은 기사·위치·상태를 읽는다. 현대 MediaBrowser와 platform legacy MediaBrowserService 인터페이스를 같은 서비스에 등록한다.
- NewsMediaCatalog는 목록·ID·제목 검색을 제공한다. debug는 기존 창작 기사3편/오디오3개/문장18구간, release는 Empty다. 외부 URI와 메타데이터를 신뢰하지 않고 등록 ID/검색 결과로 재구성한다. 외부 queue 변경·shuffle·repeat는 제공하지 않는다.
- 재생·일시정지·STOP·ID/검색 재생·이전/다음 기사를 지원한다. ±10초는 현재 기사 탐색이다. 기사 이동은 playWhenReady를 유지하고 끝에서 순환하지 않는다. 기사 끝은 현재 기사에서 일시정지하며 다음 편을 자동 재생하지 않는다.
- 실제 Car의 연결·현재 권한·GEAR_SELECTION·PERF_VEHICLE_SPEED·CarUxRestrictions를 읽는다. P/0/UXR해제 모두 확인돼야 대본·번역·목록·설정·예시 연습을 연다. 그 외에는 짧은 제목·읽기 전용 진행·미디어 조작만 표시한다. 주행 버튼76dp, 중앙 재생88dp. 기어 전환·권한 부족·연결 미확인에는 상세 화면과 대화상자를 닫는다. 진단 Activity는 DO가 아니다.
- Media3가 오디오 포커스와 foreground notification을 소유한다. Activity onStop은 재생 종료 신호가 아니다. 사용자/차량 STOP·영구 LOSS·출력 component 차단은 pause→stop으로 재생 의도를 지운다. 일시 포커스 억제는 이전 의도를 보존하지만 명시 정지 후 복귀는 자동 재생하지 않는다. task 제거는 stopMedia→stopSelf→super, 최종 서비스 종료는 세션/player release다.

## AVD 검증 · 2026-11-12

Pleos_Connect_v2_1, Connect2.1/API34 ARM64, user10, target35, GPUhost. 원본2560×1440/160dpi. 기본 앱 창[853,90][2535,1300] (1682×1210). CAR_SPEED 권한은 앱 버튼과 Android 정상 권한 창에서 허용했다. 에뮬레이터/UXR 전역 우회는 사용하지 않았다.

| 확인 | 결과 |
| --- | --- |
| debug/release/lintDebug + core JUnit | 성공, JUnit3개 |
| 서비스 실제 instrument | 현대 browse/search/ID, platform legacy browse/ID/search-play/STOP, Compat library search, 두 controller 위치/정지 공유, 기사 경계/종료, 일시·영구 focus, Activity 재생성, 실제 task 제거, 앱 소유 Car client disconnect 총9개 통과 |
| 출력 정책 adapter instrument | 실제 ExoPlayer에서 disallow 이후 PLAY/PREPARE 차단, reallow 무재개, STOP normalization1회 총2개 통과. 실제 차량 power/Suspend 신호를 재현한 테스트는 아님 |
| P→D0→이동 | UXR0→80(0x50), DOrequired=true. 대본/메뉴 제거, 실제 터치 재생·일시정지·10초 탐색·기사 이동 성공 |
| 상세→D/R/N→P | D에서 설정 닫힘. R에서는 시스템 후방 카메라가 앱을 가림; 실제 gear2/UXR80/detailsfalse 확인. N에서 연습이 남지 않고 minimal player 표시. P 복귀 시 자동 연습/재생 없음 |
| 권한·연결 미확인 | CAR_SPEED revoke 후 상세 닫힘, 정상 권한 재허용 후 P 상세 복원. 앱 소유 Car client close에서 UNKNOWN 확인. CarService 프로세스 사망/재연결은 실제 주입하지 않았으며 재구독 코드는 별도 소스 검토 |
| 미디어 키 | shell media_session dispatch play/next/pause/stop: PLAYING3→기사변경→PAUSED2→NONE0. 핸들 하드웨어 테스트는 아님 |
| Activity 가림/foreground | 진단 화면으로 가려도 PLAYING3, 서비스 isForeground=true/mediaPlayback 확인. task 제거 후 서비스 없음·재연결 무재개 |
| 다크·글자1.3·논리 좁은 창 | dark/P 및841dp 실제 논리 앱 폭(density320)·font1.3의 D 조작76/88dp 확인. 별도 물리 split-window 검증은 아님. font1.0/density160/자동 테마/P로 복원 |
| 주차 회귀 | 번역·기사정보·질문 예시·연습2회·완료 기록·설정 적용 실제 터치/캡처 확인 |
| release APK | 샘플 MP3·preview·Asta·debug schema 없음. 카탈로그 Empty |

설치된 ai.umos.mediaapp은 일반 앱 메뉴에 정상 선택 진입점이 보이지 않았다. dispatcher/Activity 직접 호출 시 해당 패키지 onCreate에서 NullPointerException으로 종료됐다. 이는 검증용 직접 실행에서 관찰한 한계이며, 정상 차량 미디어 UI 연동 성공이나 앱 자체의 원인이라고 판정하지 않는다. 표준 서비스 검색에는 NewsMediaService가 노출되고 현대/legacy client와 미디어 키는 동작했다. 실제 미디어 센터·Tune Knob·핸들·실차·Suspend·Gleo는 별도 검증이 필요하다.

Media3의 legacy active 플래그는 idle STOP에도 true로 남는다. 공개 Media3 API에 setActive는 없으며 일반 STOP은 연결을 유지한 채 재생·포커스·foreground를 정리하는 경로다. 매 STOP에 session.release를 호출하면 기존 controller가 단절되므로 최종 service 종료에서 release한다. 이를 active=false나 Pleos 최종 심사 승인으로 보고하지 않는다.

## 재현

```sh
source scripts/dev-env.sh
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :core:pleos:testDebugUnitTest :app:assembleDebugAndroidTest :core:pleos:assembleDebugAndroidTest
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb install -r --user 10 app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r --user 10 core/pleos/build/outputs/apk/androidTest/debug/pleos-debug-androidTest.apk
adb shell am instrument --user 10 -w io.github.driveenglishlab.pleos.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument --user 10 -w io.github.driveenglishlab.pleos.platform.test/androidx.test.runner.AndroidJUnitRunner
```

[Media3 구조](https://developer.android.com/media/media3/session/serve-content) · [Pleos 오디오 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide) · [AAOS 제한](https://source.android.com/docs/automotive/driver_distraction/guidelines)

[주차](parked.jpg) · [D 미디어](driving.jpg) · [841dp·큰 글자·다크](large-dark-driving.jpg) · [권한 미확인](permission-missing.jpg). 이미지는 실제 AVD 앱 창 캡처이며 차량 시스템 영역과 실제 데이터는 공개하지 않는다.

차량 legacy 이전/다음도 전체 기사 이동이다. Media3의 generic previous는3초 이후 같은 기사를 재시작하므로 generic 이전/다음 명령을 실제 기사 경계에 맞추고 전체 기사 이동으로 매핑했다. Notification 미디어 키와 modern controller의 예상 위치 계산에도 같은 규칙을 적용한다. 실제7초 이후 이전 기사, playing/paused 유지, 첫/마지막 legacy action 비활성을 instrument에서 확인했다.
