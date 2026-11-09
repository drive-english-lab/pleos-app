# 기여 규칙

1. GitHub 이슈에 변경 범위와 완료 조건을 적는다.
2. `develop`에서 `feat/<번호>-<설명>`, `fix/<번호>-<설명>`, `docs/<번호>-<설명>`, `refactor/<번호>-<설명>`, `test/<번호>-<설명>` 또는 `chore/<번호>-<설명>` 브랜치를 만든다.
3. 변경 목적별로 커밋하고 제목은 `type(scope): summary` 형식으로 쓴다. 예시는 `feat(setup): add connection diagnostics`다. 커밋에 AI 도구의 공동 작성자 트레일러를 붙이지 않는다.
4. `develop`을 대상으로 PR을 열고 이슈 번호, 검증 결과, 남은 작업을 적는다. 이슈 완료 PR에는 `Closes #<번호>`를 쓴다.
5. 배포 대상은 `main`에 둔다. 검증한 `develop`을 `main`에 릴리스 PR로 올린다.
