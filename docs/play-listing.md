---
published: false
---

# Play Console listing draft (internal)

Internal working draft for the Google Play listing of Wristline. Not part of the GitHub Pages
site (`published: false`). User-facing text is given in English (en-US) and Korean (ko-KR).
Facts here are taken from the app at git `baabbeb` (versionName 0.1.0, applicationId
`dev.wristline.watch`, minSdk 33, targetSdk 36) and the bridge at 0.1.0. Items marked **verify**
need checking against the Play Console form at submission time; console wording changes.

## 1. Store listing

**App name:** Wristline (both languages)

### Short description (max 80 characters)

| Lang | Text | Chars |
|---|---|---|
| en-US | Follow your AI coding agents and answer their prompts from your wrist. | 70 |
| ko-KR | PC에서 실행 중인 AI 코딩 에이전트를 손목에서 확인하고 응답하세요. | 39 |

Alternative naming the products (69/42 chars): "Watch and answer your Claude Code and Codex
sessions from your wrist." / "PC의 Claude Code·Codex 세션을 손목에서 확인하고 응답하세요."
**verify**: Play's metadata policy allows describing compatibility with third-party products but
not implying endorsement; keep the disclaimer at the end of the full description either way.

### Full description, en-US (max 4000 characters)

```
Wristline puts your AI coding agents on your wrist. It shows the Claude Code and Codex CLI sessions running on your computer, buzzes when one of them needs a decision, and lets you answer without going back to the desk.

REQUIRES WRISTLINE-BRIDGE ON YOUR COMPUTER
Wristline does nothing on its own. Install the open-source companion server on the machine where your agents run (npm package wristline-bridge, https://github.com/wristline/wristline-bridge), expose it over HTTPS (for example with Tailscale Funnel), and pair the watch with a one-time 6-digit code. There is no account and no cloud in between: the watch talks directly to your bridge.

WHAT IT DOES
• Session list: every Claude Code and Codex session the bridge sees, with its status (running, idle, needs input, ended) and how many requests are waiting.
• Answer requests: permission prompts and questions arrive on the watch. Allow, Always allow, Deny, or hand the decision back to the PC. Questions show their options.
• Read along: the latest messages of a session, "Earlier" to scroll back, and a context-usage arc.
• Send a message: dictate with the watch's speech recognizer or type, review it on a confirmation screen, then send (sending needs a Claude Code session running inside tmux; Codex prompts go through the app-server daemon).
• Usage: 5-hour and weekly plan limits with their reset times, per provider.
• Background alerts (optional): a foreground service keeps the connection while the app is closed, so requests arrive as vibrating notifications and finished tasks as quieter ones. Turn it on in Settings; it uses more battery.
• Try demo: browse sample sessions without a bridge to see how it works.
• English and Korean.

PRIVACY
The app talks only to the bridge address you enter, over HTTPS, with a token that stays on your watch. No developer servers, analytics, ads or crash reporting. Voice input uses the watch's system speech recognizer. Details: https://wristline.github.io/wristline/privacy

REQUIREMENTS
• Wear OS 4 or newer, designed for round Galaxy Watch displays. The watch needs internet access (Wi-Fi, LTE, or through the phone).
• A computer with Node.js 22+ running wristline-bridge (developed on Linux and WSL2).
• Claude Code and/or OpenAI Codex CLI. More providers are planned.

Wristline is open source: https://github.com/wristline/wristline

Claude Code is a product of Anthropic and Codex of OpenAI. Wristline is an independent project, not affiliated with or endorsed by either.
```

### Full description, ko-KR (max 4000 characters)

```
Wristline은 PC에서 실행 중인 AI 코딩 에이전트를 손목 위로 가져옵니다. Claude Code와 Codex CLI 세션을 워치에서 보여 주고, 에이전트가 결정을 기다리면 진동으로 알리며, 책상으로 돌아가지 않고도 응답할 수 있게 합니다.

PC에 wristline-bridge가 필요합니다
Wristline은 단독으로는 동작하지 않습니다. 에이전트를 실행하는 PC에 오픈소스 컴패니언 서버(npm 패키지 wristline-bridge, https://github.com/wristline/wristline-bridge)를 설치하고, HTTPS로 접근할 수 있게 한 뒤(예: Tailscale Funnel), 일회용 6자리 코드로 워치를 페어링하세요. 계정도, 중간 클라우드도 없습니다. 워치는 사용자의 브릿지와 직접 통신합니다.

주요 기능
• 세션 목록: 브릿지가 감지한 모든 Claude Code·Codex 세션과 상태(실행 중, 대기, 입력 필요, 종료), 대기 중인 요청 수.
• 요청 응답: 권한 요청과 질문이 워치로 도착합니다. 허용, 항상 허용, 거부, 또는 PC에서 응답. 질문은 선택지를 그대로 보여 줍니다.
• 대화 확인: 세션의 최근 메시지, "이전 항목"으로 거슬러 보기, 컨텍스트 사용량을 원형 게이지로 표시.
• 메시지 보내기: 워치의 음성 인식기를 통해 음성으로 입력하거나 직접 입력하고, 확인 화면에서 검토한 뒤 보냅니다. 메시지를 보내려면 Claude Code 세션이 tmux 안에서 실행 중이어야 하며, Codex 메시지는 app-server 데몬을 거칩니다.
• 사용량: 5시간·주간 플랜 한도와 초기화 시각을 제공자별로 표시.
• 백그라운드 알림(선택): 앱을 닫아도 포그라운드 서비스가 연결을 유지해 요청은 진동 알림으로, 완료된 작업은 조용한 알림으로 도착합니다. 설정에서 켤 수 있으며 배터리를 더 씁니다.
• 데모 보기: 브릿지 없이 샘플 세션을 둘러보며 동작 방식을 확인할 수 있습니다.
• 한국어와 영어 지원.

개인정보
앱은 사용자가 입력한 브릿지 주소와만 HTTPS로 통신하며, 토큰은 워치에만 저장됩니다. 개발자 서버, 분석, 광고, 크래시 리포트가 없습니다. 음성 입력은 워치의 시스템 음성 인식기를 사용합니다. 자세한 내용: https://wristline.github.io/wristline/privacy

요구 사항
• Wear OS 4 이상, 원형 Galaxy Watch 화면에 맞춰 설계. 워치에 인터넷 연결(Wi-Fi, LTE 또는 휴대전화 경유)이 필요합니다.
• Node.js 22 이상과 wristline-bridge가 설치된 PC(Linux, WSL2에서 개발).
• Claude Code 또는 OpenAI Codex CLI. 다른 제공자도 추가할 예정입니다.

Wristline은 오픈소스입니다: https://github.com/wristline/wristline

Claude Code는 Anthropic, Codex는 OpenAI의 제품입니다. Wristline은 독립 프로젝트이며 두 회사와 제휴하거나 승인을 받은 것이 아닙니다.
```

### Category and tags

- **Category:** Tools (alternative: Productivity). "Developer tools" is not a Play category.
- **Tags** (pick up to 5, **verify** available tags): Developer tools, Productivity, Remote control,
  Notifications, Wear OS.
- **Contact:** email required by Play (fill in); website https://wristline.github.io/wristline;
  issues https://github.com/wristline/wristline/issues.
- **Privacy policy URL:** https://wristline.github.io/wristline/privacy (must return 200 before
  submission; see release checklist).

## 2. Data safety questionnaire

Approach (from the plan): declare conservatively. Data leaving the watch goes only to a server the
user runs, and the developer never receives any of it, but Play defines "collection" as
transmission off the device, so declare it as collected. No sharing. Everything is encrypted in
transit (the app refuses `http://`).

### Overview questions

| Question | Answer | Basis |
|---|---|---|
| Does your app collect or share any of the required user data types? | Yes | Prompts, answers and the device token are transmitted to the user's bridge |
| Is all of the user data collected by your app encrypted in transit? | Yes | HTTPS/WSS only; `normalizeAddress` refuses `http://` |
| Do you provide a way for users to request that their data is deleted? | Yes | Settings › Disconnect deletes the watch's record on the bridge (`DELETE /api/device`) and clears the watch; `wristline-bridge devices --revoke` on the computer |
| Account creation / account deletion URL | Not applicable, no accounts | Pairing issues a device token, not an account with the developer. **verify** that the console accepts "no account" here |
| Independent security review | No | |
| Committed to Play Families policy | No (not a children's app) | |

### Data types

For every row: Collected = Yes, Shared = No, Processed ephemerally = No, Purpose = App
functionality only. Required or optional: **Optional (users can choose)** for the Messages and App
activity rows, since each is sent only on an explicit user action and the app is fully usable
without ever sending one; **Required** for the Device or other IDs row, since no connection works
without the token.

| Category › type (console wording, **verify**) | What it is in Wristline | Notes |
|---|---|---|
| Messages › Other in-app messages | Messages the user types or dictates for an agent (`POST /api/sessions/{id}/prompt`) | Sent only after the user confirms on the "Send this?" screen |
| App activity › Other user-generated content | Same messages, declared here too if the console asks for user-generated content; transcripts flow bridge → watch and are never transmitted from the watch | Declaring both is the conservative choice; drop one if a reviewer says it is redundant |
| App activity › Other actions | Approval decisions (Allow / Always allow / Deny / Answer on PC) and answers to questions (`POST /api/requests/{id}`), plus which session is being viewed (WebSocket subscribe) | |
| Device or other IDs | The device token (bearer), the bridge-assigned device id, and the watch name sent at pairing | The watch name defaults to the system device name and can contain a personal name; if a reviewer treats that as Personal info › Name, add that row with the same answers |

Not collected: location, contacts, photos, audio (the microphone is handled by the system
recognizer; the app receives text only), health, financial, calendar, files, installed apps,
crash logs, diagnostics, or any identifier tied to the developer.

## 3. Foreground service declaration (connectedDevice)

Play Console › App content › Foreground service permissions. Type declared in the manifest:
`connectedDevice` (`FOREGROUND_SERVICE_CONNECTED_DEVICE`). Android defines this type as
interactions with external devices over Bluetooth, NFC, IR, USB or a network connection.

**Description text (submit as is):**

```
Wristline is a Wear OS companion app for wristline-bridge, the companion program on the user's own computer (the connected device). When the user turns on "Background alerts" in Settings, the app starts a foreground service of type connectedDevice that holds a single WebSocket connection over the network to that program, so that permission prompts and questions raised by the user's coding agents (Claude Code, Codex CLI) arrive as notifications while the app is closed. There is no bulk data transfer or sync: the service only keeps that one connection open and receives small event messages. The service runs only while that setting is on and the watch is paired; it requires notification permission (without it the app cannot reach the user, so the toggle is refused), shows an ongoing activity with live counts ("2 running · 1 waiting"), stops itself when the pairing is removed or revoked, and never starts at boot. Without the service the connection is dropped shortly after the app leaves the foreground and the user would miss prompts that block their agent.
```

**Video** (required by the form; record on the watch, ~30 s, link from Drive/YouTube unlisted).
**Live bridge required**: demo mode hides the Background alerts switch and has no requests, so
the watch must be paired with a running bridge (see section 4).

1. Settings › Background alerts toggle on → ongoing "Monitoring sessions" notification appears.
2. Leave the app (swipe to the watch face). On the PC, trigger a permission prompt.
3. The watch vibrates; open the notification → Request screen → tap Allow.
4. Settings › Disconnect → the ongoing notification disappears.

**Fallback (plan S9):** if Google rejects `connectedDevice`, resubmit with `specialUse` and the
same description as the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` reason. That needs a manifest change,
which is out of scope for this document.

## 4. App access instructions (for reviewers)

```
No login or account is needed.

DEMO MODE (no bridge needed): on the welcome screen tap "Try demo". The app loads bundled sample data and shows the session list (two sample sessions), a session transcript, Usage and Settings. The sample data has no pending permission request or question, sending a message is disabled, and the "Background alerts" switch is hidden in demo mode. Settings › "Exit demo" returns to the welcome screen. Demo mode makes no network requests.

FULL FUNCTIONALITY (live bridge): permission requests, questions, Speak/Type and Background alerts need a running wristline-bridge (the open-source companion program, npm package wristline-bridge). A temporary review bridge is available for the review period:
1. Tap "Set up". If the app asks for notification permission, allow it (needed for alerts). The "Bridge address" screen follows.
2. Enter the address [FILL IN: review bridge host, for example xxxx.ts.net] and wait for "Bridge found".
3. Tap "Use a token instead" and enter this token: [FILL IN: output of `wristline-bridge pair --token --name "Play review"`]. The app connects and shows the session list.
4. Open a session: the transcript streams live; Speak or Type sends a message after the "Send this?" confirmation. When an agent asks for permission, the request appears in the session list and as a vibrating notification; Allow / Always allow / Deny / Answer on PC sends the answer.
5. Settings › Background alerts turns on the foreground service (ongoing "Monitoring sessions" notification). Settings › Disconnect removes the pairing.

Without a bridge, "Set up" cannot proceed: an address where nothing answers shows "Can't reach this address."; an address that answers but is not a bridge shows "No Wristline bridge at this address."
```

Before submission: start the review bridge on a temporary Funnel address, run
`wristline-bridge pair --token --name "Play review"`, fill in the address and token above, and
revoke the token with `wristline-bridge devices --revoke <id>` once the review is done. Play's
app-access policy expects reviewer access when core features are otherwise unreachable, and the
app supports it: the address and token can be typed in without any pairing code.

Maintainer note: the demo fixture (`assets/protocol/event-snapshot.json`, `items.json`) comes
from the bridge's `protocol/v1` via `scripts/sync-protocol.sh`, and today it holds no request,
question or Speak/Type-capable session. Making demo mode show those (so screenshots and reviewer
access no longer need a live bridge) is a bridge-side fixture change, not a watch-app change.

## 5. Content rating notes (IARC questionnaire)

- Category: Utility, Productivity, Communication, or Other.
- Violence, sexual content, language, controlled substances, gambling, horror: none.
- User interaction: No. Messages go to a program on the user's own computer, not to other people;
  there is no user-to-user communication, no sharing of user-generated content with other users,
  no location sharing.
- Purchases: none. Ads: none.
- Expected result: Everyone / PEGI 3 / equivalent.

## 6. Permissions justification

| Permission | Used for | Where in code |
|---|---|---|
| `INTERNET` | HTTPS and WebSocket connection to the user's bridge | `Bridge.kt` (OkHttp) |
| `ACCESS_NETWORK_STATE` | `registerDefaultNetworkCallback` to show "Offline" without retrying and to reconnect as soon as the network is back | `Bridge.registerNetworkCallback` |
| `POST_NOTIFICATIONS` | Request and task-update notifications; the foreground-service notification. Asked on the "Set up" path and again from Settings when turning on Background alerts | `Notifier.kt`, `NotifyScreen`, `MonitorService` |
| `FOREGROUND_SERVICE` | Runs `MonitorService` while Background alerts is on | `MonitorService.kt` |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Type of that service: keeps the network connection to the bridge on the user's computer (see section 3) | `MonitorService.onStartCommand` |
| `CHANGE_NETWORK_STATE` | Prerequisite for the `connectedDevice` foreground service type: Android requires at least one of `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `BLUETOOTH_CONNECT`, `NFC`, `UWB_RANGING` (etc.) to be held before `startForeground(..., FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` is allowed. It is the least invasive of those; no code calls network-changing APIs. Keep it and state this reason in the form | `AndroidManifest.xml`; enforced by the system in `MonitorService.onStartCommand` |
| `VIBRATE` | One haptic click when a request arrives while the app is open (`Notifier.tick`). Notification vibration goes through the channel and does not need this permission | `Notifier.tick` |

No `RECORD_AUDIO`: speech input launches the system recognizer via `RecognizerIntent`; the app
receives text only. No location, Bluetooth, storage, contacts or boot permissions.
`uses-feature android.hardware.type.watch` restricts the listing to Wear OS devices.

## 7. Assets checklist

- [ ] **Wear OS screenshots**: 1:1 aspect ratio, at least 384×384 px (Galaxy
      Watch Ultra gives 480×480 via `adb exec-out screencap -p > shot.png`), PNG, no device frame
      or bezel added, actual app UI only. At least one required; aim for 4–6. From demo mode:
      welcome, session list, session detail, Usage, Settings. **Live bridge required** for the
      permission request and question shots (demo has none). Capture a ko-KR set too
      (`adb shell cmd locale set-app-locales dev.wristline.watch --locales ko-KR`). **verify** the
      current Wear OS screenshot rules in the console.
- [x] **App icon** 512×512 PNG (32-bit, ≤1 MB) exported from the launcher icon assets:
      `branding/png/icon-512.png` (white mark on navy, like the launcher icon).
- [x] **Feature graphic** 1024×500 PNG or JPEG (required for every store listing, Wear-only
      included): `branding/png/feature-graphic-1024x500.png` (24-bit PNG, no alpha).
      Regenerate both with `python3 branding/tools/render.py`; see `branding/README.md`.
- [ ] **Privacy policy URL** live: https://wristline.github.io/wristline/privacy.
- [ ] Short and full descriptions for en-US and ko-KR (section 1), default language en-US.
- [ ] Developer contact email and, if applicable, physical address (required for paid/
      merchant accounts; **verify** for a personal developer account).
- [ ] Wear OS form factor enabled for the release track; the app is declared standalone
      (`com.google.android.wearable.standalone`).

## 8. Release checklist

- [ ] Sync protocol fixtures to the published bridge: `scripts/sync-protocol.sh 0.1.0` after
      `wristline-bridge@0.1.0` is on npm; commit the updated `app/src/main/assets/protocol/`.
- [ ] Create the upload keystore **outside the repository** and back it up (password manager +
      offline copy):
      `keytool -genkeypair -v -keystore ~/.android-keys/wristline-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`
- [ ] Put `wristline.storeFile/storePassword/keyAlias/keyPassword` in `~/.gradle/gradle.properties`
      (absolute path; see docs/dev-setup.md). Never in the repo; `*.jks` is git-ignored.
- [ ] Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload.
- [ ] `./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease` →
      `app/build/outputs/bundle/release/app-release.aab`; confirm it is signed with the upload key
      (`jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab | head`),
      not the debug key.
- [ ] Enable GitHub Pages for wristline/wristline: Settings › Pages › Deploy from branch `main`,
      folder `/docs`; check that https://wristline.github.io/wristline/privacy returns the policy.
- [ ] Play Console: create the app, enrol in Play App Signing when uploading the first AAB.
- [ ] Start the review bridge and fill in the address and token in section 4; revoke the token
      after the review.
- [ ] App content: privacy policy URL, data safety (section 2), foreground service declaration +
      video (section 3), app access (section 4), content rating (section 5), ads = No, target
      audience = 18+ / not designed for children, news app = No, COVID/health/government = No.
- [ ] Store listing (section 1) and assets (section 7).
- [ ] Upload to the **Internal testing** track first; add tester emails; install from the Play
      link on the watch, pair with a real bridge, run the full flow (pair → request → answer →
      voice prompt → background alert → disconnect).
- [ ] Wait for the **pre-launch report**: 0 crashes, review accessibility and security warnings.
- [ ] Only then promote to closed/open testing or production.
