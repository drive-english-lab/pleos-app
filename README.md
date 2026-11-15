# Pleos Android app

## Product UI port · 2026-11-15

`refactor/6-product-ui`는 제품 `1811d7e`에서 분리한 UI 개선이다. 단일 대본·실제 구간 중앙 추적·하단 중앙 재생/이전·다음 구간·현재 기사 메뉴·펼치는 설정을 실제 Compose 화면에 반영한다. 과거 debug 샘플 서비스를 병합하지 않는다. [변경 범위·렌더러 검증·제품 합류](docs/ui-product/README.md)를 따른다. 제품의 후속 음성 커밋과 합류한 실제 앱/촬영 결과는 별도 검증 대상이다.

Pleos Connect용 Android 앱이다. 기본 진입은 실제 프로필·catalog·대본·단일 기사 미디어 port에 연결한 제품 화면이다. 실제 전원 사실과 화면·미디어·직접 입력·Gleo의 허용 조건을 각각 확인한다. 연결 점검은 별도 destination으로 보존한다. schema5·Room5와 기사 질문·연습의 제품 연결 코드를 갖추며 실제 음성·전원·자료 승인을 별도 확인한다.

## 차량 public I/O 수명 보완 · CAR-LIVE-01 · 2026-11-15

차량 연결·manager 취득·등록·읽기·해제를 main 밖의 process 공유3 lane으로 분리했다. lane당 native slot1/일반 pending1, Car session1/다음 start1을 유지하고 원 단조 기한·source/operation·policy revision/dirty mask로 늦은 결과를 폐기한다. 논리 timeout은 물리 취소가 아니며 미반환 호출/정리 실패의 quota를 지우지 않는다. close는 UNKNOWN을 먼저 게시하고 원 자원 정리를 우선 예약한다. MIC-only 통지·실패는 무관한 AUDIO/MEDIA 허가를 만료시키지 않는다.

최종 선별 합성15개(0.122초)와 같은 최종 코드/설치의 target35/API34 public 진단1개(0.168초)를 확인했다. 실제 P/UXR/3component current·rawPower UNKNOWN/RECORD_AUDIO 미허용, logical close 뒤 원 unregister3개/disconnect의 public 반환을 관찰했다. 초기 property read는 snapshot에서 pending이었고 현재 값은 실제 callback 근거다. 전체 읽기 완료·물리 정리/취소·실차/실제 음성 성공으로 확대하지 않는다. 원 owner/epoch·32table·보호 bytes·QA설정/profile2를 보존했다. local 전체 debug/release/lint/domain/data/UI는 반복하지 않으며 최종 grouped PR의 자동CI를 별도 기록한다. 화면/공유 시연 정책 변경이 없어 Sites 갱신은 제외했다. [원 결과·제약](../docs/verification/learning-review-20261115/car-liveness/summary.json).

## 실제 Main 진입·빈 관심 분야 요청 보완 · 2026-11-15

같은 설치·기존 owner의 실제 Main에서 온보딩→연결 점검→뒤로와 HOME→재진입을 확인했다. 관심 분야가 비어 있을 때 Ktor가 빈 배열을 bare `topics` query key로 보내 서버에서 `validation_failed`로 거절되던 경로는 해당 키를 생략하도록 고쳤다. 비어 있지 않은 분야는 OpenAPI의 반복 키 전송을 유지한다.

최종 실제 추가 GET은 Stored·분류6개·적격 기사0개였다. 정상 Main 복원 때 보호 세션 파일은 갱신됐으며 owner·설정·outbox는 같았다. 추가 GET 전후에는 보호 파일까지 모두 같았다. 기존 소유를 초기화하거나 온보딩 완료·권리·모델 승인을 채우지 않았다. 실제 HTTP 요청 URI 회귀2개, 별도 opt-in 원세션 GET 진단1개, debug/release·앱/data 테스트 APK·lint(error0/warning37)를 확인했다. 진단 성공은 실제 뉴스 재생·설정 적용·온보딩 완료·Gleo/직접 입력·실차 전체 통과가 아니다. 앞선 전체 회귀를 새로 실행한 것으로 합산하지 않는다.

## 현재 구현 · R7 기능별 허용과 음성 초기화

원시 전원 UNKNOWN을 그대로 유지하며 Details는 실제 P·UXR·보이는 창·현재 owner로 판단한다. AUDIO/MEDIA와 MIC의 현재 값·출처 세대·권한 세대를 분리해 마이크 변경이 뉴스 재생을 취소하지 않게 했다. 실제 매체 포커스와 원 재생 명령은 별도로 검사한다. Suspend·STOP·입력 중단 뒤 늦은 저장·재생 준비가 자동 재개하지 않으며, 실제 출력 완료 사실은 원 owner/회차/저장소에 보존한다. 실제 background 실행 근거가 없는 경로는 계속 대기한다.

Gleo는 실제 sender/schema·차량 사용 범위·등록 세대와 원 언어/형태/완료 근거를 확인하며 직접 마이크의 P·MIC 제한과 구분한다. 생산 Gleo/직접 음성/뉴스 TTS의 승인값은 임의로 채우지 않았다. Google 제공 보류와 같은 설치의 보호 세션·저장·동기화·삭제 범위는 아래 R6 기준을 유지한다.

SDK3.3.1의 초기화 전에 listener를 등록하던 오류를 실제 호출로 확인해 초기화 성공 뒤 등록하도록 고쳤다. 원 instance의 정리 실패·미확인과 remove/release/focus 반납 상태를 보존하고 새 출력을 차단한다. 중립 ON_DEVICE 시험에서 EN·KO start/done과 별도 start→stop/onStop·정리를 확인했으며, 이 시험은 생산 Gateway·실제 뉴스·Gleo·실차 전체의 성공을 뜻하지 않는다.

검증은 domain90, data156개 중155통과/실연동 기본 제외1, 플랫폼 고유30개(기존28+정리 결함2), renderer UI34개 중32통과/기본 제외 진단2다. 플랫폼28과 UI32는 전체 실행 뒤 실패에 관련된 사례만 선별 확인한 고유 결과이며 단일 전체 실행 통과가 아니다. 별도 opt-in 실제 차량·식별정보 읽기 각1과 중립 음성1도 통과했다. 마지막 debug/release·테스트 APK 빌드와 lint(error0/warning37), 계약 동기화4개를 확인했다.

실제 target35 앱/SDK34에서 P·UXR 및 AUDIO/MEDIA/MIC current 정책과 저장 세션·소유·암호문 존재를 확인했다. raw power는 UNKNOWN, RECORD_AUDIO는 미허용이다. 초기 Main 시도는 OEM 화면과 top-resumed timeout/REMOVE_TASK만 관찰돼 미확인이었다. 후속 실제 Main 온보딩·진단 복귀는 위와 같이 확인했고, 전체 학습 흐름은 아직 미확인이다. 합성 캡처·격리 SDK 결과로 대체하지 않는다. [통합 검증·현재 캡처](../docs/verification/learning-review-20261115/README.md).

## 현재 제품 범위 · R6 Google 계정 제공 보류

PRD R6 CHG-17/D30을 적용해 온보딩·설정·보호 복구에서 Google 연결/병합·계정 선택·전환/로그아웃·계정 기반 새 설치 복원을 제공하지 않는다. 비활성 계정 버튼도 남기지 않고, stale/direct intent를 저장·소유 fence·credential 요청 전에 거절한다. cold start·Refresh·background sync는 보존된 계정 작업을 자동 진행하거나 취소하지 않는다. Google SDK/merge·target 코드와 과거 검증은 아래에 후속 이력으로 보존하며 Google provider/client·인증 장치 준비는 현재 버전의 필수 선행이 아니다.

지속 미로그인 프로필의 내부 익명 Auth, 유효한 같은 설치의 보호 세션 복원·동기화·전체 학습 삭제는 유지한다. 기존 linked 소유자는 그대로 표시한다. 현재 source가 확정돼 있으면 미완료 계정 준비는 안내만 남기고 같은 source의 저장·삭제를 막지 않는다. 소유 전환/손상·없는 식별정보·보호된 프로필 불일치에서는 기존 owner/암호문/selector/outbox/epoch·생성 fence를 보존하며 대기한다. 범위 보류로 새 guest·강제 logout·소유 재배정·자료 초기화를 하지 않는다. 최초의 정상 새 설치에서 내부 익명 시작을 하는 기존 경로와 식별정보 소실 뒤 복구 비보장을 구분한다.

현재 뉴스·말하기·당일 선택/난이도·기록·관심 설정·Google 뉴스 발견·4출처/두 보기와 전원/음성·권리/모델 품질·실차/사용자 검증 조건은 바뀌지 않는다. 화면은 저장/동기화·같은 설치/식별정보 소실·미전송 기록의 복구 한계만 안내하며 다른 UI 개선은 진행하지 않았다. R6 검증 결과와 현재/후속 미확인은 [통합 검증 기록](../docs/verification/learning-review-20261115/README.md)에 묶는다.

R6 코드 검증은 data 155통과/실연동 기본 제외1, 별도 임시 anonymous SDK 왕복1통과·테스트 자료 정리, UI 고유28개 중27통과/기본 제외 진단1이다. UI 결과는 전체27개 실행과 후속 보호 대기 사례 검증을 합친 고유 사례 수이며 단일28개 실행 결과가 아니다. 보호 대기를 발견하면 캐시된 VERIFIED 표시와 무관하게 접근을 즉시 닫고 같은 소유자의 재확인 성공 뒤에만 연다. 마지막 debug/release·테스트 APK 빌드와 lint(error0/warning37)를 통과했다.

AVD 시작 ANR·키가드/창 미생성 실패는 별도 기록했다. Android guest 재부팅 후 같은 보호 파일의 byte 보존과 렌더러3캡처를 확인했지만 실제 제품 Main 화면·읽기 전용 진단은 성공으로 확인하지 못했다. 합성 화면·실제 SDK 임시 저장소와 실제 제품 설치의 정상 복구/차량 검증을 구분한다.

아래 T052/병합 복구와 R8/R7의 Google 실행·수치는 당시 코드/검증 이력이며 R6 반영 결과나 현재 필수 Google 검증으로 읽지 않는다.

## T052 선택 로그인·연결 계정 복원 · 2026-11-15

설정의 새 Google 연결/기존 계정 병합/연결 계정 전환과 복구 안내의 연결 계정 복원을 제품 intent로 연결했다. 보호된 연결 재확인은 새 anonymous 생성을 허용하지 않는다. 세션 없는 설치의 복원은 별도 target 인증·서버의 최신 linked profile/epoch·영속 전환 journal·암호화 selector CAS를 사용하며 원래 암호문/creation fence/미전송 자료를 보존한다. 기존 계정 전환은 자동 병합하지 않는다. 취소·차량/창 변화·늦은 결과는 실제 시도 권한을 재검사하고 저장 복구와 재생을 구분한다.

기존 계정 병합의 source 차단은 target SDK 인증·보호 저장과 현재 source 재검사 뒤로 미뤘다. Auth HTTP 실패·입력 권한 소실·인증 대기 중 epoch/revision 변경은 기존 owner·outbox·암호문을 덮지 않는다.

실제 Google SDK의 명시 선택 옵션에 autoSelect=false/filterAuthorizedAccounts=false와 hashed nonce를 설정하고, Supabase에는 raw nonce를 전달한다. 매 요청의 공개 제공자 설정·실제 client/기기 지원을 확인하며 token/nonce/계정 정보는 UI state/로그에 남기지 않는다. 공개 OAuth web client ID는 local.properties 또는 환경의 GOOGLE_WEB_CLIENT_ID로 주입하며 Google client secret은 앱에 넣지 않는다.

최종 domain73·data138통과/실연동1제외·기존/신규 UI18통과/진단1제외와 별도 재검증 credential7통과, debug/release/test APK·lint(error0/warning37)를 확인했다. 최초 compiler의 owner smart-cast, 테스트 Activity의 Main Looper, 실제 SDK의 자동 선택 기본값을 각각 수정했고 마지막 인증7개에는 실제 fetch 도달과5초 제한을 검증했다. 전체26 UI 사례의 고유 최종 결과는25통과/진단1제외이며 반복 실행을 새 사례로 합산하지 않는다.

**실제 Google 제공자는 비활성이고 client ID가 없으며 actualPower는 UNKNOWN이다.** 기존 MainActivity는 복구 안내/비활성 복원 행동을 유지한다. 실제 보호 재확인 후 암호문 hash가 같고 session 없는 creation fence가 유지됨을 확인했다. 실제 계정 로그인·복원 성공, provider 활성화/기기 지원·전원·차량을 검증했다고 표시하지 않는다. T052/T057은 미완료로 유지한다. Sites는 로컬 예시4캡처만 반영했고 배포하지 않았다. [최종 검증·캡처](../docs/verification/learning-review-20261115/README.md).

## T052 병합 실패 복구 보완 · 2026-11-15

원 complete body/hash/operation과 target subject/session/installation/epoch를 보호 저장하며 성공 불명은 같은 요청만 재조정한다. complete의 실제 `MERGE_EXPIRED` 409만 확정 미병합으로 받아 명시적인 ‘계정 전환 다시 확인’에서 source 인증·최신 anonymous profile/epoch·미전송 ACK·같은 target을 검사한 뒤 연결된 새 prepare/complete를 만든다. background는 새 proof를 발급하지 않는다. body는 source fence 전에 고정하고 중단·응답 유실·원/새 intent 계보를 보존한다. 완료한 옛 계보가 다음 계정 전환을 막지 않도록 terminal journal을 pending 조회에서 제외했다.

legacy body 미확인, 일반 conflict·stale epoch·target 변화는 불명/해당 사유의 대기로 보존한다. 기존 SQL의 target epoch 검사가 receipt보다 앞서므로, target 삭제 뒤 원 요청을 확정할 수 없는 경우 자동 새 병합으로 우회하지 않는다. 서버 cb6c904의 좁은 오류 매핑만 연계하며 공개 wire/DB 변경과 실제 Google 인증은 실행하지 않았다.

최종 검증: domain73, AVD data146개(145통과·실연동 opt-in1제외), 새 복구6개 별도 통과, 관련 계정 화면4개 통과. debug/release·test APK·lint(error0/warning37)와 계약 동기화4개를 통과했다. 최초 새 fixture6개 실패는 합성 proof의 최소32자 위반을 수정하고 전체146개를 재확인했다. 실제 계정/차량 조건은 위 T052/T057의 미완료 상태를 유지한다.

## R8 현재 구현·검증 · 2026-11-15

대기 중인 같은 기사 B에 대해 계속 기다리기·원문 듣기·다른 준비 뉴스를 각각 명시적으로 선택한다. 듣던 A의 실제 위치와 전역 설정은 보존하고, 대안은 준비된 다른 기사만 고른다. 새 읽기 전용 GET은 원 요청/해시/현재 사용자·서버 메타데이터를 확인하며 구형 서버에서 POST로 우회하지 않는다. 앱 재시작·실패·늦은 응답은 저장 상태만 복원하고 자동 재생하지 않는다.

Room5, 판정 재검토, 관심 뉴스/주요 이슈, 사용자별 설정 초안, Gleo 명령 adapter와 Pleos TTS3.3.1 의존성을 연결했다. 승인 데모 로더는 별도 사람 검수와 파일 해시·구간·24조합을 검증하며 승인 자산이 없으면 비활성이다. SDK 클래스가 debug/release APK에 포함된 것과 실제 음성 프로젝트 승인은 다르다.

최종 검증은 domain73, AVD data133통과/실연동 opt-in1제외, platform19, Compose14통과/읽기 전용 진단 opt-in1제외다. debug/release·instrumentation APK와 lint(error0/warning37), 계약 동기화4개가 통과했다. 최초 data3실패는 실제 nonce 검사 순서와 source6 JSON 저장/비교 불일치를 수정하고 data134개를 재확인했다. 늦은 재생은 대본/권리 확인 이후에도 원 intent generation으로 차단한다.

실제 개발 DB35개 이력과 기존 데이터/RLS 보존, 서버 Auth/API25+신규67+기존47조건을 확인했다. AVD의 별도 임시 SDK 저장소에서도 같은 프로필 복원·설정 왕복/정리가 통과했다. **기존 MainActivity 설치에는 저장된 세션 없이 creation fence가 남아 있어 보호 상태를 유지한다.** 읽기 전용 진단으로 확인했고 데이터 삭제·새 guest 생성으로 감추지 않았다. 이 설치의 정상 로그인/전체 학습 재생 성공으로 표시하지 않는다.

실제 전원 신호는 UNKNOWN이며 Gleo/TTS 프로젝트·직접 mic·주행/실차, 승인 기사/고정 데모·출처 운영·모델 품질과 사용자 성과는 남아 있다. Sites는 로컬 concept4캡처를 확인했고 공유 배포하지 않았다. [최종 검증·캡처](../docs/verification/learning-review-20261115/README.md) · [실제 DB/API 기록](../docs/verification/learning-review-20261115/runtime-r7.md) · [승인 데모 입력 형식](core/data/review-demo-format.md).

아래 R7/R6와 초기 연결 절은 각 시점의 이력이며 현재 완료 수·가용성과 구분한다.

## R7 기준 이력 · 2026-11-15

뉴스의 `내 관심 뉴스/최근 주요 이슈`, 기존 stream을 재사용하는 자동 진행·동일 기사 이어하기, 판정 재검토의 접수/현재 결과 분리와 owner별 설정 초안 보존을 연결했다. Room5 보존 이행과 독립 assessment-review/catalog-views/review2 strict 계약을 사용하며 기존 closed 계약은 유지한다. under_review/withheld·실패는 과거 성공으로 표시하지 않고 실제 권한/자료가 없으면 unavailable로 둔다.

최신 로컬 검증은 domain **70개**, AVD data **89통과·실연동 opt-in1제외**, platform **16개**, Compose **10개**다. data는 기존86건(85통과/1제외)과 마지막 초안4건을 합친 값이다. debug/release·instrumentation APK 빌드 성공, lint **error0/warning37**, 계약 동기화4개 통과. 앱 기준 R6 HEAD `65dc5c7` 이후 로컬 변경을 검증한 결과이며 아직 후속 커밋/PR/CI 결과로 표시하지 않는다.

같은 개발 DB의 보존 migration은 **13→33개 실제 적용**됐고 서버의 실제 Auth/API R7 **25조건**·기존 호환 **47조건**이 통과했다. 이는 Android SDK/실제 뉴스·음성의 통합 성공과 다르다. Root가 진행 중인 **AVD→localhost Auth/API 연결은 아직 성공 표시하지 않는다**.

실제 Gleo/TTS/직접 mic·power/주행/실차, 기사별 원문 권리·승인 데모·수집 운영 및 OpenAI/Decisions 품질·운영 승인은 남아 있다. D24의 새 B 브리핑 대기→B 원문 명시 선택은 독립 review3 명세·분석 인계 대기이며 A/전역 설정을 바꾸는 우회는 제공하지 않는다. [최신 검증·캡처](../docs/verification/learning-review-20261115/README.md) · [실제 DB/API](../docs/verification/learning-review-20261115/runtime-r7.md).

아래 R6 절은 기준 커밋 당시의 이력이다. 영구 migration 미적용 등 당시 상태를 현재 상태로 읽지 않는다.

## R6 기능 묶음 · 2026-11-15

독립 review/preparation/source6 계약, Room4 보존 이행, 구간 TTS·공통 출력 lease, 주차 말하기 모달, 실제 노출·도움 관찰, 듣기만 설정·난이도·추천·말/표현 기록과 계정 전환·삭제를 연결했다. 실제 원문·작업·권한을 확인할 수 없는 경로는 unavailable을 유지한다. Gleo는 전달/음성 경로이며 영어 평가와 난이도 판단은 검증된 서버 추론 경계에서 처리한다.

기능 묶음 검증: domain64개, AVD data70개 통과·실제 연동 opt-in1개 제외, platform12개, Compose7개 통과. debug/release 빌드와 lint 통과(error0/warning37). Compose2개와 data1개의 오래된 기대값을 현행 동작에 맞춰 수정한 뒤 관련 범위를 재검증했다. Gradle lock의 기존 의존성 버전은 유지하고 누락 configuration을 기록했다.

AVD는 Pleos_Connect_v2_1, emulator-5556/user10이다. 화면 캡처는 합성 renderer와 실제 창에서 검증했으며 실제 뉴스·Auth·Gleo·차량 흐름 전체의 성공을 뜻하지 않는다. 실제 전원/음성 승인, 콘텐츠 권리, 서버 migration 영구 적용과 실차 확인은 남아 있다. R7 평가 재검토·공통 자동 진행·뉴스 보기 확장은 이 기준 묶음 이후 별도로 구현한다. [검증 기록](../docs/verification/learning-review-20261115/README.md)

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

승인된 공개 콘텐츠는 Supabase 직접 읽기, 개인 기록·추론은 사용자 세션을 검증하는 별도 LearningApi 경로다. 현재 진단은 SDK로 프로젝트 게이트웨이에 응답을 요청하고 DataStore에 마지막 점검 시각을 저장한다. 익명 Auth 세션 생성/복원과 프로필·기본 설정은 실제 검증했다. 이후 R7 서버의 보존 DB 적용·Auth/API 경계도 확인했으며, 앱 localhost SDK 연결과 승인 자료/음성·차량 전체 흐름은 별도 검증 중이다. 사용자 세션 port에 서버용 secret/model key를 넣지 않는다.

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

## 같은 답변의 원언어 확인 · T036-E1 · 2026-11-15

주차 중 직접 입력의 final이 원언어 미확인·형태 unknown일 때 같은 연습 화면에서 한 번만 “방금 답변을 영어로 했나요?”를 묻는다. 영어 선택은 원래 capture/owner/epoch/질문과 묶인 독립 확인 명령을 journal에 저장한 뒤 서버 receipt를 요청한다. receipt만으로 답변을 수락하지 않으며 현재 입력 권한·포커스·세대와 원문 hash를 다시 검사하고 기존 답변/outbox와 확인 intent를 한 transaction으로 수락한다. 한국어·모름 선택은 원래 미확인 입력 경로를 유지한다.

전송 실패는 같은 명령의 명시 재시도만 허용한다. 취소·이동·권한/포커스/owner/epoch 변경 뒤 늦은 응답은 새 답변이 되지 않는다. 아직 수락하지 않은 인식 문장은 RAM에만 두며 화면에 인용하지 않는다. 재시작 시 남은 metadata intent는 orphan 처리하고 녹음/후보를 복원하지 않는다. 기존 closed 계약·Room 테이블/버전·음성 승인 설정은 유지했다.

검증: 최종 domain103개, AVD 데이터 경계8개와 화면 renderer4개, 서버 Python241개·격리 SQL52개를 구분해 확인했다. 로컬 묶음 debug/release/lint(error0/warning37) 후 최종 경합 guard에 필요한 domain/debug/test APK만 갱신했고 최종 release/lint는 PR CI로 확인한다. 실제 STT/Gleo 입력·정상 원격 receipt·실차·학습 품질 검증은 남아 있다. Sites는 같은 프로젝트용 정적 캡처3장과 범위 설명만 로컬 반영했으며 interactive mock 연결·배포는 하지 않았다.

## ON_DEVICE 제품 출력과 주차 직접 답변 · 2026-11-15

T040은 원 SDK DONE의 완료 사실과 cleanup·다음 출력 허용을 분리하고, 같은 원 완료 증거를 local journal v2에 원자 보존한다. 이전 구간 이동·cold phase ACK는 완료·정리 hold를 바꾸지 않는다. 정상 Application/runtime의 제품 출력 opt-in은 실제 scope·권리 확인한 manifest가 있을 때만 실행한다.

T036은 Pleos SpeechToText3.3.1 ON_DEVICE 입력 port와 기존 generic 입력을 명시 설정으로 분리한다. 입력 처리 안내·review가 없으면 비활성이다. 명시 답변 마치기+final+정상 ended+cleanup+원 lease가 모두 맞을 때만 기존 E1/답변 접수로 이어지고, 정상 마이크 정리는 E1 세대를 유지한다. 미접수 text는 RAM 한정이며 원어/형태는 UNKNOWN을 유지한다. init5초·정리2초·전체60초와 실제 권한/현재성 재검사, process 공유 cleanup hold를 적용했다. 실제 STT/Gleo·외부 처리/보관·영어/원어·실차 승인은 미확인이다.

최종 debug/release·lint(error0/warning37), domain103, data6, platform 고유28(최초27통과/1실패 뒤 관련STT12통과), 화면9를 확인했다. 캡처 저장 경로만 test private files로 보완한 관련 renderer2 재실행은 같은 사례이며 합산하지 않는다. 대상5556/user10 기존32table·17row·보호2파일의 전후 hash는 모두 같다. 실제 뉴스/제품 출력 opt-in은 승인 자료/scope 부재로 미실행이다. [묶음 검증·캡처](../docs/verification/learning-review-20261115/on-device-integration/)에 초기 실패와 수정 결과를 함께 남겼다. Sites는 같은 프로젝트용 정적 합성 캡처만 로컬 추가하며 기존 interactive WIP·공유 범위·배포는 유지한다.
