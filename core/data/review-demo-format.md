# 로컬 설정 데모 입력

`ReviewedSettingsDemoLoader(context, trustedApproval, nowEpochMs).load()`는 APK의
`review-demo/manifest.json`만 읽는다. 현재 제품에는 승인 데모 자산이나 승인 기본값을
추가하지 않았다. 사람의 원문·권리·변경 표시 확인을 마친 뒤 별도로 주입한
`DemoApproval`이 없거나 파일/검증 근거가 맞지 않으면 `null`이다.

승인 입력은 JSON 안에서 읽지 않는다. 승인 구성 담당은 실제 검수 기록의 파일 SHA-256,
출처 검토 참조, 권리 검토 참조, 유효기간과 철회를 제공해야 한다. 합성 테스트의 승인
콜백을 제품에 연결하지 않는다. 로더 성공은 음성 SDK 권한이나 실제 출력 성공을 뜻하지 않는다.

파일은 UTF-8, 최대 512 KiB이며 SHA-256은 공백을 포함한 **파일 원바이트** 기준이다.
중복 키와 아래에 없는 키는 거부한다. nullable 필드도 키를 생략하지 않는다.

| 위치 | 필수 필드와 형식 |
| --- | --- |
| root | `schema_version:1`, `demo_id:UUID`, `version:integer>=1`, `source`, `original_full`, `briefing`, `followups`, `questions` |
| source | `publisher:nonempty string`, `original_url:string|null`, `published_at_raw:string|null`, `published_at_utc:string|null`, `synthetic:boolean` |
| excerpt | `text:nonempty string`, `text_hash:SHA256`, `language:en|ko`, `segments:SegmentManifest` |
| SegmentManifest | 기존 domain `SegmentManifest.canonicalPayload()` 구조 + `manifest_hash` |
| followups | `original_en_summary`, `original_ko_summary`, `original_ko_translation`, `briefing_ko_summary`, `briefing_ko_translation` 각각 excerpt |
| questions | `basic`, `intermediate`, `advanced` 각각 영어 excerpt |

실제 출처는 HTTPS 원문 링크·최초 게시 원문 문자열·UTC 시각이 필요하다. 링크에 사용자
정보를 허용하지 않는다. 원문/브리핑/질문과 영어 요약은 영어이며 나머지 후속은 한국어다.
각 excerpt는 코드 포인트 기준 처음부터 끝까지 순서대로 누락 없이 덮는 기존 segment
검증과 script/segment/manifest hash 검증을 거친다. 원문은 단일 값이며 단계별로 바꾸지
않는다. 로더는 두 형식 × 네 후속 × 세 단계 전체를 확인하며 브리핑의 영어 요약 후속은
생략한다. 별도의 실뉴스 selection, lesson, resume, API, 음원 파일은 만들지 않는다.

`ReviewedSettingsDemoLoaderTest`의 인라인 자료와 신뢰 콜백은 검증 전용 합성 입력이다.
실제 사람이 확인한 출처/권리 승인을 대체하거나 제품 자산으로 활성화하지 않는다.
