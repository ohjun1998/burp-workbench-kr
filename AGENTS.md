# Burp Workbench KR development
<!-- main-merge-0.53.4: 56b56e961d0d73e777228e60fdfc1a1e544496fd; pr-head: 64cc557bab399ae27d239ea928c266e810daf85d; pr: 94; merged-at-utc: 2026-10-27T05:19:48Z -->

- Parallel Simple release: **Workbench Simple 1.3.2** is merged to main through PR #100 (`70a9476998764211b886fa392742ec11d3acf83c`, 2026-10-28T10:11:02Z). Source/build commands live under `simple/`; use `python simple/dev.py check` and `python simple/dev.py verify` for Simple work. Guide: `simple/README_KR.md`; exact checks/limits: `simple/docs/VERIFICATION_SIMPLE_1.3.2.json`. Preserve all immutable Simple versioned artifacts and packaged inputs; changes after publication require a new version. Embedded Mapper0.3.6/helper0.4.1 are separate from the original Next versions below. Main merge verification is recorded in `docs/reports/2026-10-28-simple-main/MERGE_KR.md`.

- GitHub is the shared source of truth. Read `README.md`, `PROJECT_STATUS_KR.md` and `docs/COLLABORATION_KR.md` before collaboration work.
- Use a new task branch from current main: `account-a/<task>` or `account-b/<task>`. Do not overwrite the other account's branch or force-push main.
- Deliver changes through a PR with actual checks and a short Korean handoff. Keep merged project status in `PROJECT_STATUS_KR.md`; track in-progress ownership in Issues and PRs.
- GitHub synchronization covers commits and work records, not the other ChatGPT account's conversation or usage quota. Never claim repository connection supplies terminal credentials automatically.

- Communicate in Korean. Current main extension: Workbench Next 0.53.4, merged through PR #94 at 2026-10-27 14:19:48 (Asia/Seoul); merge `56b56e961d0d73e777228e60fdfc1a1e544496fd`, final PR head `64cc557bab399ae27d239ea928c266e810daf85d`; based on 0.53.3 main `e0b40e3dae3fcca21d3cd9441260e3edae65d159`. Embedded Feature Mapper: 0.3.5; lab: 3.10.0. Preserve 0.53.3, immutable 0.53.2 and all prior artifacts/history.
- Workbench Next 0.53.3 was developed from main `6737746f1a0c29c42c91a246a8161192c7d279d6` and merged as `5b8b8576a93e7ca15cef5a8e06bc9b93d2d1301f` through PR #91. Mapper remains 0.3.5 and lab 3.10.0. The eight usability changes include a navigation-bar width/reflow fix that preserves remaining-check explanation space. Source `511845348ffdea146ee0a01f377c6e427c72390c` passed final Linux/Windows checks and full/focused synthetic layout in Actions run 40414755887. Versioned JAR/source artifacts have verified checksums, source fingerprints and CRC; evidence is in `docs/VERIFICATION_0.53.3.json`. This does not claim native Burp or manual browser validation.
- Preserve the immutable 0.53.2 candidate files and validation history. Its initial candidate Actions run 40409443813 passed, but PR #91's broader UI regression failed the remaining-check explanation-height gate at 1024px with a wide menu. It was the unmerged candidate before the 0.53.3 correction; do not describe it as a completed release or as final 0.53.3 evidence.
- Workbench Next 0.53.4 fixed-scope stabilization is completed and merged to main. Verified source `937c42defbc4d76187f1d189b36b84f4272dd76a` passed final Actions run 40451792261: Linux 4,774 and Windows 4,774 checks; full 576 views/8,088 controls/246 states and focused 414 views/4,308 controls/246 states with all gates zero. Full/focused checks overlap and are not summed. The eight accepted improvements are complete; do not extend this task with new proposals. Native Burp, DPI/IME, Docker and live external/manual browser checks were not run.
- Read `DEVELOPMENT_SETUP_KR.md`, `이어가기_인수인계.md` and the current release guide before changing features.
- Current release guide: `docs/RELEASE_0.53.4_NEXT_KR.md`; verification: `docs/VERIFICATION_0.53.4.json`. Prior 0.53.3 and 0.53.2 guides/evidence remain historical. Update merge and verification status only from actual evidence.
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



