# Native design preview

Issue #6의 디버그 화면이다. 공통 Media3 및 실제 차량 상태 연결은 [003 검증](../driving-media/README.md)을 따른다. 긴 대본과 하단 중앙 재생, 오른쪽 연습을 Compose로 확인한다. 제품 기능 작업과 독립된 `feat/6-native-news-preview`, [PR #7](https://github.com/drive-english-lab/pleos-app/pull/7)에서 관리한다.

## 포함 범위

- 창작 기사3편, 영어 원문 오디오3개, 실제 PCM 길이를 측정한 문장 구간18개.
- 공통 MediaLibraryService/MediaLibrarySession/ExoPlayer 재생과 MediaController 연결. 재생·일시정지·10초 탐색·기사 이동. Activity 가림은 재생을 중단하지 않는다. STOP·영구 포커스 상실·task 제거는 자동 재개되지 않는 정지다.
- 얇은 제목·형식 행, 대본만 스크롤하는 읽기 영역, 화면 하단 정중앙의 재생·10초 이동. 위치는 왼쪽, 언어·따라가기는 오른쪽. 본문과 도크는 독립 행이다.
- 문장 강조·자동 스크롤·직접 읽기 후 현재 문장 복귀. 한국어 전체 번역은 영어 문장에 임의로 동기화하지 않는다. 재생 구간이 바뀌어도 한국어 읽기 위치를 초기화하지 않는다.
- 기사 목록·출처 정보·내용 질문 예시, 질문·시연 답변 선택·입력 예시·확인·한 가지 피드백·재시도·완료, 메모리 기록·테마 적용·기존 연결 점검.
- 충분한 답변·표현 교정·사실 오류·인식 불확실·통신 오류 예시. 재요청 시 같은 답변을 유지하고 재시도 횟수는 화면 왕복에도 유지한다.
- 공통 스크롤 페이지와 고정 주요 행동. 좁은/큰 글자에서는 도움을 접고 조작을 다시 배치한다.48dp 이상 터치 영역.
- Asta Sans400/500/600, 블루와 밝게·어둡게·자동 테마.

오디오·글꼴·아이콘·Activity·launcher 변경은 `src/debug` 전용이다. 003에서 Media3·foreground mediaPlayback·차량 읽기 권한과 적응형 debug host의 distractionOptimized 선언을 추가했다. 실제 P·속도0·제한 해제일 때만 상세 화면을 열며 진단 Activity는 DO가 아니다. 릴리스 launcher와 기존 연결 점검 코드를 유지한다. OFL1.1과 Lucide ISC 라이선스는 debug assets에 보관한다.

## 이전 UI AVD 검증 · 1470722

아래 표는 Media3 도입 전 결과다. 이후 D 조작과 수명 정책은 [003 검증](../driving-media/README.md)에서 갱신했다.

Pleos Connect2.1 / API34 ARM64 / `Pleos_Connect_v2_1` / user10. 전체2560×1440,160dpi, 이번 앱 창 `[853,90][2535,1300]`(1682×1210). 웹16:9 전체 캔버스와 비율이 다르다. ADB 캡처는 앱 창만 잘라 공개한다.

| 검증 | 확인 결과 |
| --- | --- |
| debug/release assemble, lintDebug | 성공 |
| release APK 자산 검사 | preview·MP3·Asta 리소스 없음 |
| 설치·실행, 재생·정지·이동 | 성공.0:00→0:01,10초 앞으로3회→0:31. 정지 위치 유지 |
| AudioFocus | GAIN 소유자 등록 확인 |
| 기본 재생 배치 | 대본919px 높이, transport 중심 x1694 = 앱 창 중앙 |
| 목록·출처·내용 질문·번역·복귀 | 실제 터치·캡처 확인 |
| 연습 재시도·왕복·완료 기록 | 두 번째 답변 후 마치기, 기록 완료 확인 |
| 피드백 예시와 실패 복구 | 사실 오류·인식 불확실·오류 후 같은 답변 재요청 확인 |
| 테마·font_scale1.3 | 실제 화면 확인 후 font_scale1.0·자동/밝은 화면 복원 |
| Virtual Console P→D0→P | D는 DrivingState1, UxRestrictions80(0x50), DOrequired=true. 시스템이 앱 전체를 가리고 연습 터치 차단. P복원 |

D 상태 UIAutomator에는 가려진 앱의 트리가 남아 있다. 시각 차단과 터치 결과는 ADB screencap과 실제 조작으로 확인했다. 웹의 대본 DOM0과 구분한다. 축소한 합성 task bounds에서 번역 스크롤을 검증하려던 추가 실험은 입력 포커스가 불안정해 결과로 사용하지 않았다. 원래 창과 P 상태로 복원했다.

에뮬레이터는 `-gpu host`로 실행한다. 자동 선택 소프트웨어 렌더러에서 DrivingView Unreal 프로세스 종료·입력 차단이 있었고 앱 예외와 구분한 환경 문제다. AVD 데이터를 초기화하지 않았다.

## 실제 연동과 구분

말하기는 선택한 예시 답변으로 진행하며 실제 마이크·STT·평가·Gleo·Supabase·계정는 연결하지 않았다. 기록·설정은 시연 실행 상태다. 이 native 시연은 원문 오디오만 지원한다. 웹의 브리핑·후속 듣기·계정 설정 전체를 구현한 범위는 아니며 설정 화면에도 안내한다. 대본·출처·직접 입력은 주차 정책을 유지한다. 003에서 실제 D 상태의 미디어 조작을 확인했으며, 실차·핸들 하드웨어·실제 Suspend와 차량 미디어 앱의 정상 선택 화면은 확인하지 못했다.

[밝은 화면](light.jpg) · [어두운 화면](dark.jpg) · [질문](practice.jpg) · [피드백](feedback.jpg) · [기록](history.jpg) · [설정](settings.jpg). 공개 데이터는 창작 기사뿐이다.
