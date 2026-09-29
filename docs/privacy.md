---
title: Wristline Privacy Policy
permalink: /privacy
layout: default
---

# Wristline Privacy Policy

Effective date: 2026-09-29

Wristline is a Wear OS app that lets you follow and control AI coding-agent sessions (Claude Code,
OpenAI Codex CLI) running on your own computer, through Wristline Bridge, an open-source companion
server that you run yourself. This page says what the app stores, what it sends, and where.
A Korean translation follows the English text ([한국어](#ko)).

## Summary

- The app talks to exactly one place: the bridge address you entered, over HTTPS.
- There are no developer servers, analytics, ads, crash reporting or telemetry. We, the
  developers, never receive anything from the app.
- The watch stores five small values: the bridge address, the device token, a device id, the
  watch name, and whether background alerts are on.
- Session lists, transcripts, requests and usage figures are held in memory only and are never
  written to storage; transcripts are released when you leave the session, the rest when the app
  process ends.
- Disconnect in Settings, or uninstalling, removes everything on the watch. The bridge keeps one
  device record (name, hash of the token, pairing time) that you can delete on your computer.

## What the app stores on the watch

Everything below lives in app-private storage (SharedPreferences) and is excluded from Android
cloud backup and device-to-device transfer.

| Value | What it is | Written when |
|---|---|---|
| Bridge address | The `https://host[:port]` you entered | After the address check |
| Device token | Long-lived bearer token issued by your bridge at pairing (or one you typed from `wristline-bridge pair --token`) | Pairing |
| Device id | Id your bridge assigned to this watch at pairing (empty when you typed a token) | Pairing |
| Watch name | Name the bridge lists this watch under; defaults to the device name in the watch's system settings, or the watch model if that is empty | Settings |
| Background alerts | On or off | Settings |

Nothing else is written to storage. Session lists, transcripts, pending requests and usage figures
are held in memory only (at most about 200 transcript items per open session) and disappear when
the screen closes or the app process ends. The app keeps no history and never writes transcript
text to logs.

## What is transmitted and to whom

Everything goes to the single bridge address you configured, and only over HTTPS: the app refuses
`http://` addresses. TLS certificates are checked by the watch's operating system; the app adds no
exceptions.

Sent to your bridge:

- During setup: a health check of the address you typed, without a token, to confirm a Wristline
  bridge answers there.
- At pairing: the 6-digit one-time code and the watch name. The bridge answers with the device
  token.
- With every later request and with the WebSocket connection: the device token (Authorization
  header).
- Which session you are viewing, so the bridge streams that session's items.
- Messages you type or dictate for an agent, only after you confirm them on the "Send this?"
  screen.
- Your answers to permission prompts and questions: Allow, Always allow, Deny, Answer on PC, or
  the options you pick.
- When you tap Disconnect: a request to delete this watch's record on the bridge.

Received from your bridge: the session list (title, working folder, status, context size),
transcript items, pending requests (for example the tool an agent wants to run and its input),
alerts (needs input, finished) and plan-usage figures. All of it originates from agent files and
hooks on your own computer.

Never sent to us: the developers run no servers, and the app contains no analytics, advertising,
crash-reporting or telemetry code. We cannot see your address, token, transcripts or anything
else.

The network path between the watch and the bridge (for example Tailscale Funnel, a Cloudflare
tunnel, or a reverse proxy you set up) is chosen and operated by you. The app only knows the HTTPS
address.

## Third parties

None. The app shares data with no third party and bundles no third-party SDKs beyond the
open-source libraries it needs to run (OkHttp, AndroidX, Kotlin). Two things sit outside the app
and follow their own policies:

- **Speech recognition.** "Speak" opens the watch's system speech recognizer (Android
  `RecognizerIntent`, provided by the watch maker or Google). That recognizer handles the
  microphone and the audio under its maker's privacy policy. Wristline has no microphone
  permission and never records audio; it receives only the recognized text, shows it to you, and
  sends it to your bridge only when you confirm.
- **Your tunnel or network provider.** If you publish the bridge through Tailscale Funnel or a
  similar service, that provider's terms cover the connection. The bridge README notes that a
  Funnel host name appears in public certificate logs.

Wristline Bridge itself is open-source software running on your computer; what it keeps is
described under "Data retention and deletion".

## Notifications

Notifications appear only if you allow them. They are built on the watch from the WebSocket data;
no push service (such as Firebase Cloud Messaging) is involved.

- **Requests** (vibrating): the request title, usually a tool name such as "Bash", and the session
  title or working-folder name. When an agent waits for input without an open request, the same
  channel shows the session title and the agent's waiting message (up to about 120 characters),
  or "Needs input".
- **Task updates**: the session title and the first line (up to about 120 characters) of the
  agent's last reply, or "Finished".
- **Background connection**: with background alerts on, a silent ongoing notification such as
  "Monitoring sessions · 2 running · 1 waiting".

Session titles, tool names and a snippet of the agent's last reply can therefore show on the
watch face and in the notification history. Each channel can be turned off in the watch's app
settings. Tapping a notification opens the matching screen; a notification is removed once its
request is answered or the session moves on.

## Background alerts (foreground service)

With **Settings › Background alerts** on, a foreground service of type `connectedDevice` keeps the
single WebSocket connection to your bridge while the app is closed, so requests arrive as
notifications. It runs only while that switch is on, stops itself when the pairing is removed or
revoked, and does not start at boot (it resumes the next time you open the app). It uses more
battery than leaving it off.

## Data retention and deletion

On the watch:

- **Settings › Disconnect** clears everything the app stored (address, token, id, name, setting),
  ends the connection and, when the bridge is reachable, asks it to delete this watch's record.
- **Uninstalling** removes all of the app's storage. Backup and device transfer are disabled, so
  no copy exists in Samsung or Google backups.
- Notifications are cleared when their request or session is resolved, or when you dismiss them.

On your computer (Wristline Bridge):

- The bridge keeps one record per paired watch in `~/.config/wristline/config.json` (file mode
  0600): a device id, the watch name, the SHA-256 hash of the token (never the token itself) and
  the pairing time.
- Remove it with Disconnect on the watch or `wristline-bridge devices --revoke <id>` on the
  computer; that watch's connections close immediately.
- The bridge keeps no other record of the watch. Its console output (journald, if you installed
  the systemd service) stays on your machine.
- A message you send becomes part of the agent's own session on your computer, as if typed there.
  Its storage follows that agent and the AI provider's terms (Anthropic, OpenAI), not this policy.

## Children

Wristline is a developer tool and is not directed at children under 13 (14 in Korea), or under
the age of digital consent where you live. We do not knowingly collect information from children;
the app collects nothing for us in any case.

## Changes

Changes to this policy are published at this address with a new effective date. Material changes
are also mentioned in the app's release notes on GitHub. Earlier versions are in the git history of
the wristline repository.

## Contact

This policy is provided by **[FILL IN: developer name exactly as shown in Play Console]** for the
Wristline app.

Email: **[FILL IN: contact email registered in Play Console]** (for anything you would rather
not post publicly)

Questions and requests: https://github.com/wristline/wristline/issues (public)

Source code: app https://github.com/wristline/wristline, bridge
https://github.com/wristline/wristline-bridge

---

<a id="ko"></a>

# Wristline 개인정보처리방침

시행일: 2026-09-29

Wristline은 사용자의 PC에서 실행 중인 AI 코딩 에이전트 세션(Claude Code, OpenAI Codex CLI)을
워치에서 확인하고 제어하는 Wear OS 앱입니다. 앱은 사용자가 직접 실행하는 오픈소스 서버 Wristline
Bridge와만 통신합니다. 이 문서는 앱이 무엇을 저장하고, 무엇을 어디로 보내는지 설명합니다.

## 요약

- 앱이 통신하는 곳은 단 하나, 사용자가 입력한 브릿지 주소이며 HTTPS만 사용합니다.
- 개발자 서버, 분석(애널리틱스), 광고, 크래시 리포트, 텔레메트리가 없습니다. 개발자는 앱에서
  아무것도 받지 않습니다.
- 워치에는 브릿지 주소, 기기 토큰, 기기 ID, 워치 이름, 백그라운드 알림 켜짐 여부의 다섯
  가지 값만 저장합니다.
- 세션 목록, 대화 내용, 요청, 사용량은 메모리에만 있고 저장소에 쓰지 않습니다. 대화 내용은 세션
  화면을 나갈 때, 나머지는 앱 프로세스가 끝날 때 해제됩니다.
- 설정의 연결 해제 또는 앱 삭제로 워치의 데이터가 모두 지워집니다. 브릿지에는 기기 기록(이름,
  토큰의 해시, 페어링 시각) 하나만 남으며 사용자의 PC에서 삭제할 수 있습니다.

## 워치에 저장하는 것

아래 값은 모두 앱 전용 저장소(SharedPreferences)에 저장되며 Android 클라우드 백업과 기기 간
전송에서 제외됩니다.

| 값 | 내용 | 저장 시점 |
|---|---|---|
| 브릿지 주소 | 입력한 `https://host[:port]` | 주소 확인 후 |
| 기기 토큰 | 페어링 시 브릿지가 발급한 장기 베어러 토큰(또는 `wristline-bridge pair --token`으로 직접 입력한 토큰) | 페어링 |
| 기기 ID | 페어링 시 브릿지가 이 워치에 부여한 ID(토큰을 직접 입력한 경우 비어 있음) | 페어링 |
| 워치 이름 | 브릿지가 이 워치를 표시하는 이름. 기본값은 워치 시스템 설정의 기기 이름(없으면 워치 모델명) | 설정 |
| 백그라운드 알림 | 켜짐/꺼짐 | 설정 |

그 밖에는 아무것도 저장하지 않습니다. 세션 목록, 대화 내용, 대기 중인 요청, 사용량은 메모리에만
있으며(열린 세션당 최대 약 200개 항목) 화면을 닫거나 앱 프로세스가 끝나면 사라집니다. 앱은 기록을
남기지 않으며 대화 내용을 로그에 쓰지 않습니다.

## 전송하는 것과 전송 대상

모든 통신은 사용자가 설정한 하나의 브릿지 주소로만, HTTPS로만 이루어집니다. 앱은 `http://` 주소를
거부합니다. TLS 인증서 검증은 워치 운영체제가 하며 앱은 예외를 두지 않습니다.

브릿지로 보내는 것:

- 설정 중: 입력한 주소에 Wristline 브릿지가 있는지 확인하는, 토큰 없이 보내는 상태 확인 요청.
- 페어링 시: 6자리 일회용 코드와 워치 이름. 브릿지는 기기 토큰으로 응답합니다.
- 이후 모든 요청과 WebSocket 연결: 기기 토큰(Authorization 헤더).
- 지금 보고 있는 세션(브릿지가 그 세션의 항목을 스트리밍하도록).
- 에이전트에게 입력하거나 음성으로 입력한 메시지. "이 메시지를 보낼까요?" 화면에서 확인한 뒤에만
  보냅니다.
- 권한 요청과 질문에 대한 응답: 허용, 항상 허용, 거부, PC에서 응답, 또는 선택한 항목.
- 연결 해제를 누를 때: 브릿지에서 이 워치의 기록을 삭제해 달라는 요청.

브릿지에서 받는 것: 세션 목록(제목, 작업 폴더, 상태, 컨텍스트 크기), 대화 항목, 대기 중인 요청(예:
에이전트가 실행하려는 도구와 그 입력), 알림(입력 필요, 완료), 플랜 사용량. 모두 사용자의 PC에
있는 에이전트 파일과 훅에서 나온 것입니다.

개발자에게는 전송되지 않습니다. 개발자는 서버를 운영하지 않으며 앱에는 분석, 광고, 크래시 리포트,
텔레메트리 코드가 없습니다. 개발자는 주소, 토큰, 대화 내용 등 어떤 것도 볼 수 없습니다.

워치와 브릿지 사이의 네트워크 경로(예: Tailscale Funnel, Cloudflare 터널, 직접 구성한 리버스
프록시)는 사용자가 선택하고 운영합니다. 앱은 HTTPS 주소만 알 뿐입니다.

## 제3자

없습니다. 앱은 어떤 제3자에게도 데이터를 제공하지 않으며, 실행에 필요한 오픈소스 라이브러리(OkHttp,
AndroidX, Kotlin) 외의 제3자 SDK를 포함하지 않습니다. 다음 두 가지는 앱 밖에 있으며 각자의 정책을
따릅니다.

- **음성 인식.** "말하기"는 워치의 시스템 음성 인식기(Android `RecognizerIntent`, 워치 제조사 또는
  Google 제공)를 엽니다. 마이크와 음성 데이터는 그 인식기가 제작사의 개인정보처리방침에 따라
  처리합니다. Wristline에는 마이크 권한이 없고 음성을 녹음하지 않습니다. 앱은 인식된 텍스트만 받아
  화면에 보여 주고, 사용자가 확인했을 때만 브릿지로 보냅니다.
- **터널·네트워크 제공자.** Tailscale Funnel 같은 서비스로 브릿지를 공개하면 그 연결은 해당
  서비스의 약관을 따릅니다. 브릿지 README에 있듯 Funnel 호스트 이름은 공개 인증서 로그에 나타납니다.

Wristline Bridge 자체는 사용자의 PC에서 실행되는 오픈소스 소프트웨어이며, 무엇을 보관하는지는
아래 "보관과 삭제"에 있습니다.

## 알림

알림은 사용자가 허용한 경우에만 표시됩니다. 알림은 WebSocket으로 받은 데이터로 워치 안에서
만들어지며 푸시 서비스(예: Firebase Cloud Messaging)를 거치지 않습니다.

- **응답 요청**(진동): 요청 제목(보통 "Bash" 같은 도구 이름)과 세션 제목 또는 작업 폴더 이름. 열린
  요청 없이 에이전트가 입력을 기다릴 때는 같은 채널에 세션 제목과 에이전트의 대기 메시지(최대 약
  120자) 또는 "입력 필요"가 표시됩니다.
- **작업 알림**: 세션 제목과 에이전트의 마지막 답변 첫 줄(최대 약 120자), 또는 "작업을 마쳤습니다".
- **백그라운드 연결**: 백그라운드 알림이 켜져 있을 때 "세션 모니터링 중 · 실행 2 · 대기 1" 같은
  무음 상시 알림.

따라서 세션 제목, 도구 이름, 에이전트의 마지막 답변 일부가 워치 화면과 알림 기록에 나타날 수
있습니다. 워치의 앱 설정에서 채널별로 끌 수 있습니다. 알림을 탭하면 해당 화면이 열리고, 요청에
응답하거나 세션 상태가 바뀌면 알림은 사라집니다.

## 백그라운드 알림(포그라운드 서비스)

**설정 › 백그라운드 알림**을 켜면 `connectedDevice` 유형의 포그라운드 서비스가 앱을 닫아도 브릿지와의
WebSocket 연결 하나를 유지해 요청이 알림으로 도착하게 합니다. 이 스위치가 켜져 있는 동안만 실행되고,
페어링이 해제되거나 취소되면 스스로 멈추며, 부팅 시 자동 시작하지 않습니다(다음에 앱을 열 때 다시
시작). 꺼 둘 때보다 배터리를 더 씁니다.

## 보관과 삭제

워치에서:

- **설정 › 연결 해제**는 앱이 저장한 모든 것(주소, 토큰, ID, 이름, 설정)을 지우고 연결을 끊으며,
  브릿지에 연결할 수 있으면 이 워치의 기록 삭제를 요청합니다.
- **앱 삭제**는 앱의 저장소를 모두 제거합니다. 백업과 기기 간 전송이 꺼져 있으므로 Samsung이나
  Google 백업에 사본이 남지 않습니다.
- 알림은 해당 요청이나 세션이 처리되면, 또는 사용자가 지우면 사라집니다.

PC에서(Wristline Bridge):

- 브릿지는 페어링된 워치마다 `~/.config/wristline/config.json`(파일 권한 0600)에 기록 하나를
  보관합니다: 기기 ID, 워치 이름, 토큰의 SHA-256 해시(토큰 자체는 저장하지 않음), 페어링 시각.
- 워치의 연결 해제 또는 PC에서 `wristline-bridge devices --revoke <id>`로 삭제할 수 있으며, 그
  워치의 연결은 즉시 끊깁니다.
- 브릿지는 워치에 관한 다른 기록을 남기지 않습니다. 브릿지의 콘솔 출력(systemd 서비스로 설치했다면
  journald)은 사용자의 PC에 남습니다.
- 워치에서 보낸 메시지는 PC에서 직접 입력한 것처럼 에이전트의 세션 기록에 포함됩니다. 그 보관은
  해당 에이전트와 AI 제공자(Anthropic, OpenAI)의 정책을 따르며 이 방침의 범위 밖입니다.

## 아동

Wristline은 개발자용 도구이며 만 14세 미만(대한민국) 또는 거주 지역의 디지털 동의 연령 미만 아동을
대상으로 하지 않습니다. 아동의 정보를 의도적으로 수집하지 않으며, 애초에 앱은 개발자에게 어떤 정보도
보내지 않습니다.

## 변경

이 방침의 변경은 새 시행일과 함께 이 주소에 게시합니다. 중요한 변경은 GitHub 릴리스 노트에도
적습니다. 이전 버전은 wristline 저장소의 git 기록에 있습니다.

## 문의

이 방침은 Wristline 앱에 대해 **[기입 필요: Play Console에 표시되는 개발자 이름]**이(가) 제공합니다.

개인정보 문의처(이메일): **[기입 필요: Play Console에 등록한 문의 이메일]** (공개하고 싶지 않은
내용은 이메일로)

문의와 요청: https://github.com/wristline/wristline/issues (공개)

소스 코드: 앱 https://github.com/wristline/wristline, 브릿지
https://github.com/wristline/wristline-bridge
