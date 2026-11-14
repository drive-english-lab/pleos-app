---
version: alpha
colors:
  background: "#F7F8FA"
  surface: "#FFFFFF"
  primary: "#3182F6"
  action: "#1B64DA"
  text: "#191F28"
  secondary: "#4E5968"
  selected: "#E8F3FF"
  divider: "#D9DFE7"
typography:
  body:
    fontFamily: "Asta Sans, sans-serif"
    fontSize: "32px"
    lineHeight: "50px"
  heading:
    fontFamily: "Asta Sans, sans-serif"
    fontSize: "36px"
    lineHeight: "46px"
omitted:
  - section: spacing
    reason: "Android dp values are maintained in the Compose theme; CSS lengths do not define vehicle measurements."
  - section: rounded
    reason: "Android dp shapes are maintained in the Compose theme."
components:
  playback:
    backgroundColor: "#3182F6"
    textColor: "#FFFFFF"
---

## Overview

Pleos Connect 운전자의 영어 뉴스 듣기 앱이다. 사용자 선택인 블루·Asta Sans와 얇은 기사 헤더, 긴 대본, 별도 하단 중앙 재생 도크를 유지한다. 장식 이미지·파형을 추가하지 않는다. 시각 참고는 UI 커밋1470722이며 고정 예시 답변·오디오·업무 상태는 제품에 복사하지 않는다.

행동 기준은 [제품 UI 계약](../../docs/specs/001-news-speaking-loop/contracts/product-ui.md)과 [차량 상태 명세](../../docs/product/design/vehicle-screen-spec.md)다. 해당 문서가 UX 계약의 원본이므로 별도 동등한 상태 정책을 만들지 않는다.

## Colors

기존 [블루 화면 설계](../../docs/product/design/screen-design.md)의 의미별 색을 따른다. 밝은 바탕은F7F8FA, 콘텐츠는FFFFFF, 글자 버튼은1B64DA, 큰 미디어 버튼은3182F6이다. 어두운 테마는 바탕171B22·면202630·글자F4F6F9·주요 조작79ADFF·보조AEB8C5를 사용한다.

런타임 단일 소유자는 `ui/theme/DriveEnglishTheme.kt`다(Model B). 이 문서는 승인된 값의 의도를 기록하며 독립 토큰을 생성하지 않는다. 화면은 MaterialTheme 의미별 색을 소비한다.

## Typography

Asta Sans400/500/600을 본문/메뉴/제목에 배분한다. 대본32sp/50sp, 제목32sp 이상·본문/질문28sp 이상·보조24sp 이상이다. frontmatter의px 표기는 문서 도구용이며 Android sp나 실제 차량 픽셀과 동일한 단위가 아니다. OFL과 Lucide 라이선스는 main assets/licenses에 보관한다.

## Layout

하단 도크는 본문과 별도 행이다. 넓은 창은 대본과 보조 영역을 나누고, 좁은 창은 한 열과 여러 행 도크로 재배치한다. 좌우 영역을 같은 폭으로 두어 transport가 실제 앱 창 중앙에 놓인다. 본문 마지막 문장을 고정 도크로 덮지 않는다.

주요 터치는76dp 이상, 재생은88dp, 가능한 간격24dp다. 각 화면의 LazyColumn만 본문을 스크롤하며 주요 행동은 별도 하단 행에 둔다. 대본은 실제 승인 cue가 없으면 일반 문단으로 제공한다.

## Elevation & Depth

기본은 평면과 구분선이다. 확인창만 Material3 Dialog의 플랫폼 창/포커스 처리를 사용한다.

## Shapes

재생은 원형, 콘텐츠/상태 영역은16dp, 일반 조작은 Material3의 공통 shape를 사용한다.

## Components

| 기능 | canonical owner | 상태/행동 원본 |
| --- | --- | --- |
| 화면/작업 | SessionCoordinator | SessionScreen·owner/work·typed completion |
| 외형 | DriveEnglishTheme | 의미별 색·Asta·sp/dp |
| 버튼/선택 행 | 공통 ProductButton/ChoiceRow | Material3 semantics·76dp·busy/disabled |
| 대본 | NewsReader | 고정 script/hash/언어별 scroll, 실제 텍스트 |
| 미디어 조작 | PlaybackDock | 실제 Media3/ListeningRepository projection |
| 설정 | SettingsDraft | 실제 settings/version과 미적용 초안 구분 |
| 표시 설정 | DeviceAppearanceStore | 기기별 system/light/dark, profile wire와 독립 |
| 상태/오류 | 공통 상태 패널 | 준비/기기 저장/ACK/충돌/미확인 구분 |

## Do's and Don'ts

차량/UXR/window/power/owner를 각각 확인한다. UNKNOWN을 허용으로 채우지 않는다. 재구성·뒤로 가기·GET·SDK callback 전송은 녹음/재생/학습 완료의 근거가 아니다. 단계 검증은 기능 묶음 마무리에 모아서 수행한다. 미준비는 복구 경로와 함께 표시한다.
