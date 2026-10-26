# Burp Workbench KR development

- GitHub is the shared source of truth. Read `README.md`, `PROJECT_STATUS_KR.md` and `docs/COLLABORATION_KR.md` before collaboration work.
- Use a new task branch from current main: `account-a/<task>` or `account-b/<task>`. Do not overwrite the other account's branch or force-push main.
- Deliver changes through a PR with actual checks and a short Korean handoff. Keep merged project status in `PROJECT_STATUS_KR.md`; track in-progress ownership in Issues and PRs.
- GitHub synchronization covers commits and work records, not the other ChatGPT account's conversation or usage quota. Never claim repository connection supplies terminal credentials automatically.

- Communicate in Korean. Current main extension: Workbench Next 0.53.1, merged through PR #89 on 2026-10-27 (Asia/Seoul); based on 0.53.0 commit `8b548422f12d498aef7334ebb86b55762c1a1569`; embedded Feature Mapper: 0.3.5; lab: 3.10.0. Preserve the 0.53.0 work history and prior 0.52.0, 0.50.0 and 0.49.1 release files. Describe verification only from current evidence.
- This task branch provides the Workbench Next 0.53.2 usability candidate from main `6737746f1a0c29c42c91a246a8161192c7d279d6`. Current main remains 0.53.1 until a merge is confirmed. Embedded Mapper remains 0.3.5 and lab 3.10.0; preserve all prior versioned artifacts. Candidate source `ba04e3ff96114bddbc14d83015c2cb920477ebbd` passed Linux/Windows regression and isolated HTTP checks plus synthetic Swing layout in Actions run 40409443813; versioned JAR/source artifacts have verified checksums and source fingerprints. Verification/publication evidence: `docs/VERIFICATION_0.53.2.json`. This does not claim native Burp or manual browser validation.
- Read `DEVELOPMENT_SETUP_KR.md`, `이어가기_인수인계.md` and the current release guide before changing features.
- Current candidate guide: `docs/RELEASE_0.53.2_NEXT_KR.md`; main 0.53.1 guide: `docs/RELEASE_0.53.1_NEXT_KR.md`. Update validation and merge status only from actual evidence; prior release counts are historical evidence only.
- Main source: `project/burp-development-workspace/burp-workbench-kr/burp-workbench/src/main/java`.
- Tests: the adjacent `src/test/java`; lab source: `project/burp-development-workspace/community-security-lab`.
- Build and verify with `python dev.py check`. `python dev.py doctor` checks prerequisites. Explicit Java/javac paths can be supplied.
- Use `build_all.py` for the standalone release. The supplied Maven POM still identifies 0.18.0 and is not the release packaging path.
- Keep prior release evidence distinct from tests executed in this environment. Historical validation JSON and screenshots are not current validation.
- Preserve original release JAR and handoff evidence under `release/` and `history/`.
- Use isolated loopback lab servers and temporary Burp profiles. Never alter the user's existing Burp, browser or proxy settings as part of automated tests.
- Actual Burp requires a separate installation and adapted native harness. Do not claim native Burp, GUI, browser DOM, Docker, live Collaborator or external BApp verification unless executed.
- On functional changes, report before/after behavior, appropriate tests, and remaining limitations; provide JAR and source artifacts.

- For a version update, run `python dev.py release` and commit versioned `downloads/` alongside source. Never overwrite an existing version. CI checks download/source fingerprints.

- Every GitHub upload or update must include the related user-visible conversation under `docs/chat-logs/YYYY-MM-DD/<task>.md` using the Asia/Seoul date. Record available user requests and assistant replies, distinguish verbatim text from summaries, redact credentials, and never include hidden reasoning or raw tool outputs. `tools/record_chat.py` provides an idempotent writer. This is a standing user requirement.



