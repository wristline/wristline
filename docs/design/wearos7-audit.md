# Wristline × Wear OS 7 가이드 점검 (전체 보고서)

- 기준 코드: `wristline` 커밋 `d1242ff` (2026-10-01 17:07). 다른 에이전트가 편집 중이라 줄 번호는 조금 바뀔 수 있음.
- 점검 방식: 읽기 전용. 파일은 하나도 고치지 않음. 공식 문서(developer.android.com), Google Maven 메타데이터, androidx 소스(GitHub 미러), 로컬 Gradle 캐시의 1.7.0 AAR 클래스 목록으로 확인.
- 기기: Galaxy Watch Ultra, Wear OS 7 (One UI 9 Watch), 480×480 원형(약 226dp).

---

## 0. 한 문단 요약

전체적으로 이미 Wear OS 6/7 세대 방식(Material 3 Expressive 부품, AppScaffold/ScreenScaffold, TransformingLazyColumn + 변형 효과, 최신 안정 라이브러리 1.7.0)을 잘 따르고 있어서, "틀을 갈아엎을" 일은 없다. 가장 중요한 빈틈은 하나다. Wear OS 6부터 targetSdk 36 앱은 화면이 어두워져도(앰비언트) 앱이 그대로 켜진 채 남는데, 앱은 이것을 "삼성 시계의 특이한 버릇"으로 보고 화면 꺼짐 방송(SCREEN_OFF)으로 우회하고 있다. 공식 방법(`LocalAmbientModeManager`)으로 바꾸면 스피너 정지, 1초 카운트다운 정지 같은 배터리·번인 처리까지 한 번에 된다. 또 백그라운드 알림(Ongoing Activity)이 켜져 있으면 시계가 시계 화면으로 돌아가지 않고 앱에 머무를 수 있어(공식 문서상 동작) 실기기 확인이 필요하다. 나머지는 대부분 작은 손질이다: 스플래시 화면(품질 기준 WO-V15), 아이콘 버튼을 표준 크기와 `touchTargetAwareSize`로, 클릭 안 되는 카드는 1.7.0의 "클릭 없는 Card"로, 6칸 코드 피커의 좁은 터치 영역, TalkBack이 건너뛸 수 있는 32dp 미만 줄들. Wear OS 7의 새 기능(위젯, Live Updates, 한 손 제스처, AppFunctions)은 아직 알파이거나 Pixel 전용이거나 이 앱의 "상시 감시" 용도에는 정책상 맞지 않아 보류가 맞다.

---

## 1. 먼저 알아둘 Wear OS 7 사실 (공식 문서로 확인)

- Wear OS 7 = Android 17 = API 37. 공식 설정 가이드는 `compileSdk 37`, `targetSdk 37`로 테스트하라고 함.
  - https://developer.android.com/training/wearables/versions/7/setup
- Play 요구사항: Wear OS 앱은 2026-08-31부터 API 35 이상이면 됨. 지금 `targetSdk 36`은 통과.
  - https://support.google.com/googleplay/android-developer/answer/11926878
- Wear OS 7 동작 변경은 2가지뿐:
  - 백그라운드 오디오 강화(모든 앱): 화면이 꺼졌거나 액티비티가 안 보이면 소리 재생·오디오 포커스·볼륨 변경이 조용히 막힘.
  - 로컬 네트워크 권한(targetSdk 37 앱만): LAN 기기와 통신하려면 `ACCESS_LOCAL_NETWORK` 런타임 권한 필요.
  - https://developer.android.com/training/wearables/versions/7/changes
  - https://developer.android.com/about/versions/17/changes/bg-audio
- Wear OS 6부터(지금 시계에도 해당): 화면이 어두워질 때 "직전 앱이 보이는 채로 resumed 상태 유지". targetSdk 36 이상 앱은 "always-on으로 간주"되고, 화면은 어둡게, 갱신은 최소 1분에 한 번 정도.
  - https://developer.android.com/training/wearables/versions/6/changes
  - https://developer.android.com/training/wearables/always-on
- Wear OS 7 새 기능:
  - Live Updates: Ongoing Activity의 "권장 업그레이드 경로". 단, 정책상 "상시 백그라운드 상태 감시", "다른 사람이 일으킨 일", "채팅·알림"에는 쓰면 안 됨.
    - https://developer.android.com/training/wearables/versions/7/features
    - https://developer.android.com/develop/ui/views/notifications/live-update
  - Ongoing Activity는 폐지 아님. "Ongoing Activity 또는 Live Update" 둘 다 품질 기준 WO-V4를 충족.
    - https://developer.android.com/training/wearables/notifications/ongoing-activity
  - Wear 위젯(타일의 후속, Glance + Remote Compose). 라이브러리는 아직 알파(`androidx.glance.wear:wear` 1.0.0-alpha19, Remote Compose 1.0.0-alpha20).
    - https://developer.android.com/training/wearables/widgets
  - 한 손 제스처(더블 핀치, 손목 돌리기): "현재 Pixel Watch 3 이상만 채택"이라고 공식 문서에 명시.
    - https://developer.android.com/design/ui/wear/guides/patterns/gestures
  - AppFunctions: 얼리 액세스(EAP).
- Compose for Wear OS 1.7.0(2026-09-23 안정판) 주요 추가: 한 손 제스처 API, TransformingLazyColumn 개선(PinnableContainer, 나가는 애니메이션). 1.6.0에서 `reverseLayout`, 스냅, `minimumVerticalContentPadding`, `LocalAmbientModeManager`, Navigation 3 지원 추가.
  - https://developer.android.com/jetpack/androidx/releases/wear-compose

---

## 2. 우선순위 표

### 지금 바로 (작고 안전한 손질)

| # | 무엇 | 위치 | 심각도 |
|---|---|---|---|
| Q1 | 스플래시 화면 추가 (검은 배경 + 48dp 아이콘, 런처 아이콘과 동일) | `AndroidManifest.xml:29`, `MainActivity.kt:44` | 중 |
| Q2 | 아이콘 버튼을 표준 크기(48/52dp)와 `touchTargetAwareSize`로 (Ask의 4개 줄은 40dp 유지 + 터치 영역 명시) | `SessionList.kt:81,181-187`, `Ask.kt:95,376-399`, `SessionDetail.kt:126,464,473` | 중하 |
| Q3 | 클릭이 없는 카드는 1.7.0의 "클릭 없는 Card"로 | `Ask.kt:436-437`, `Usage.kt:212-217` | 중하 |
| Q4 | 32dp보다 낮은 목록 줄(캡션, 작업 중 스피너)에 최소 높이, "보냄/실패"는 live region | `SessionDetail.kt:405-430`, `Ask.kt:452`, `Settings.kt:313-316`, `Common.kt:544` | 중 |
| Q5 | 크라운만 스냅하고 손가락 스크롤은 스냅 안 하는 화면 맞추기 | `Onboarding.kt:71,122,215`, `Settings.kt:184`, `Request.kt:343` | 하 |
| Q6 | 펼친 도구 상세 글자 10sp → 12sp | `SessionDetail.kt:808` | 하 |
| Q7 | "허용됨/거부됨" 확인창 1.2초 → 기본(4초) 또는 2초 이상 | `Request.kt:81,195,203` | 하 |
| Q8 | 확인창의 긴 초안은 3줄 넘으면 왼쪽 정렬 | `Ask.kt:179`, `SessionDetail.kt:261` | 하 |
| Q9 | 손으로 누른 동작의 진동은 시스템 표준(Confirm/Reject/SegmentTick)으로 | `Haptics.kt:113` 호출부 | 하 |
| Q10 | 읽어주기(TTS)는 화면을 떠나거나 꺼질 때 멈춤 | `Reader.kt:127-131` | 중하 |

### 다음 (중간 크기, 효과 큼)

| # | 무엇 | 위치 | 심각도 |
|---|---|---|---|
| N1 | 앰비언트(화면 어두워짐)를 공식 API로 처리: 스피너·카운트다운·게이지 멈춤, 연결 전경/배경 판단 | `MainActivity.kt:32-51`, `App.kt:63`, `Common.kt:363,503`, `Ask.kt:478`, `SessionDetail.kt:405-415,571-598` | 상 |
| N2 | 모니터링 Ongoing Activity 때문에 시계 화면으로 안 돌아가는지 실기기 확인, 의도 결정 | `MonitorService.kt:98,107-111` | 중 (확인 필요) |
| N3 | 페어링 코드 피커 6칸×30dp → 터치 영역 넓히기 | `Onboarding.kt:357-371` | 중 |
| N4 | 세션 대화 화면을 `reverseLayout = true`로 (채팅·로그용 공식 패턴) | `SessionDetail.kt:310-325,353` | 중 |
| N5 | 다이내믹 컬러(시계 화면 색) 선택 옵션, 상태색은 고정 유지 | `Theme.kt:30-65,91` | 중하 |
| N6 | 큰 글꼴에서 사용량 표를 "축소"하지 말고 줄바꿈 | `SessionList.kt:365` | 중하 |
| N7 | targetSdk 37 준비: 로컬 네트워크 권한 필요 여부, TTS 오디오 테스트 | `build.gradle.kts:24`, `Address.kt`, `Reader.kt` | 중 (계획) |
| N8 | 세션 화면에서 시계(TimeText) 숨김 재검토 | `SessionDetail.kt:344` | 하 (디자인 결정) |
| N9 | 토스트 8곳 → 실패 확인창 또는 화면 안 메시지 | `Ask.kt:144,200,251`, `AskHistory.kt:82`, `Common.kt:479`, `Reader.kt:80,112,117` | 하 (공식 근거 약함) |

### 보류 (크거나 선택)

| # | 무엇 | 이유 |
|---|---|---|
| L1 | Navigation 3로 이전 (`compose-navigation3`) | 지금 내비가 옛 M2.5 라이브러리와 navigation 2.6을 끌고 옴. 이점은 있으나 화면 전체 손질 |
| L2 | Wear 위젯 또는 타일 (대기 요청 수, 5시간 사용량) | 위젯 라이브러리 알파. 타일(1.6.2 안정)은 가능하지만 새 기능 |
| L3 | 컴플리케이션(시계 화면 작은 칸: 대기 수, 사용량 %) | 새 기능, 안정 API |
| L4 | Live Updates | 상시 감시는 정책상 금지 용도. 사용자가 시계에서 직접 시작한 짧은 작업에만 고려 |
| L5 | 한 손 제스처 | 공식 문서상 Pixel Watch 전용. Galaxy에선 효과 없음 |
| L6 | AppFunctions (Gemini 연동) | 얼리 액세스 |
| L7 | 카드 누름 축소 효과(커스텀) 정리 | 라이브러리에 없는 커스텀 모션. "라이브러리 모션만" 방침과 맞출지 결정만 |

---

## 3. 상세 항목

각 항목: 위치 / 가이드가 말하는 것(URL) / 지금 앱 / 심각도 / 고칠 방법.

### N1. 앰비언트(화면 어두워짐) 처리 — 심각도 상

- 위치: `MainActivity.kt:32-51` (SCREEN_OFF/ON 방송 수신기), 영향 화면 `Common.kt:363`(SmallSpinner), `Common.kt:503`(1초 카운트다운), `Ask.kt:478`(Thinking 큰 스피너), `SessionDetail.kt:405-415`(작업 중 스피너), `SessionDetail.kt:571-598`(가장자리 게이지).
- 가이드:
  - Wear OS 6+: 직전 앱이 보이는 채로 resumed 유지. targetSdk 36 앱은 always-on으로 간주. https://developer.android.com/training/wearables/versions/6/changes
  - 앰비언트에서는 "진행 표시기 같은 애니메이션을 모두 멈추라", 자주 바뀌는 값은 `--` 같은 자리표시로 바꾸라, 화면의 85% 이상은 검게. 번인 보호 기기에서는 가장자리 10px 안에 중요한 것을 두지 말라. https://developer.android.com/training/wearables/always-on
  - 공식 API: `rememberAmbientModeManager()` + `LocalAmbientModeManager`, `AmbientMode.Ambient(isBurnInProtectionRequired, isLowBitAmbientSupported)`, `AmbientTickEffect`. 1.7.0 AAR에 클래스 있음(확인).
- 지금 앱: 화면 꺼짐 방송으로 "떠남"을 흉내 냄(주석: "Some watches keep the activity resumed"). 이건 특정 시계 버릇이 아니라 Wear OS 6+의 정해진 동작. 화면 쪽 처리는 없음: 어두운 화면에서도 스피너가 돌고, 연결 배너는 1초마다 갱신, 게이지는 화면 가장자리 2dp.
- 고칠 방법:
  1. `App.kt`에서 `val ambient = rememberAmbientModeManager()`를 만들고 `CompositionLocalProvider(LocalAmbientModeManager provides ambient)`로 감쌈.
  2. `SmallSpinner`, Thinking 스피너: 앰비언트면 정지된 아이콘(또는 숨김).
  3. `ConnBanner`의 "N초 후 재시도": 앰비언트면 숫자 대신 고정 문구.
  4. `EdgeGauges`: 앰비언트면 숨김(가장자리라 번인 이동에 잘림).
  5. `Bridge.toBackground()`/`foreground` 판단을 SCREEN_OFF 대신 `currentAmbientMode`로(필요하면 방송은 보조로 유지).
- 확인 방법: 실기기에서 손목을 내려 앰비언트로 → 스피너 정지, 숫자 고정 확인. 에뮬레이터는 `adb shell input keyevent KEYCODE_SLEEP` 등.

### N2. Ongoing Activity가 "시계 화면 복귀"를 막을 수 있음 — 심각도 중 (실기기 확인 필요)

- 위치: `MonitorService.kt:98` (`open = MainActivity.openIntent`), `:107-111` (`setTouchIntent(open)`).
- 가이드: 앰비언트에서 일정 시간 뒤 시스템은 시계 화면으로 돌아가는데, "Wear OS 5+에서는 Ongoing Activity로 이것을 막을 수 있다. 터치 인텐트가 always-on 액티비티를 가리키면 된다." https://developer.android.com/training/wearables/always-on
- 지금 앱: 모니터링 알림의 터치 인텐트가 바로 MainActivity(targetSdk 36이라 always-on으로 간주)를 가리킴. 그래서 모니터링을 켠 채 앱을 열어두고 손목을 내리면 시계 화면으로 안 돌아가고 앱이 계속 떠 있을 수 있음.
- 고칠 방법(선택):
  - 의도라면(작업 감시 중엔 앱 유지) 그대로 두되 N1을 반드시 같이 해서 배터리·번인 처리.
  - 의도가 아니라면: 실기기에서 먼저 재현 → 재현되면 앰비언트 진입 후 일정 시간 지나면 목록 화면으로 정리하거나, 사용자 설정의 시간 제한을 존중하는 방법을 고르기. (어떤 우회가 Galaxy에서 먹히는지는 공식 문서로 확인 못 함.)

### Q1. 스플래시 화면 — 심각도 중

- 위치: `AndroidManifest.xml:29` (`Theme.DeviceDefault`), `MainActivity.kt:44`.
- 가이드: 품질 기준 WO-V15 "앱 시작 때 검은 배경 위 48x48dp 아이콘, 런처 아이콘과 같아야 함". `androidx.core:core-splashscreen` 사용 권장, 원형 아이콘 48dp, `Theme.SplashScreen` 부모, `postSplashScreenTheme`.
  - https://developer.android.com/docs/quality-guidelines/wear-app-quality
  - https://developer.android.com/training/wearables/apps/splash-screen
- 지금 앱: 스플래시 설정 없음(시스템 기본에 맡김).
- 고칠 방법: `core-splashscreen:1.2.0` 추가 → `res/values/themes.xml`에 `Theme.App.Starting`(부모 `Theme.SplashScreen`, `windowSplashScreenBackground=@android:color/black`, `windowSplashScreenAnimatedIcon=@mipmap/ic_launcher` 또는 48dp 전경, `postSplashScreenTheme=@android:style/Theme.DeviceDefault`) → 액티비티 테마로 지정 → `onCreate`에서 `installSplashScreen()`을 `super.onCreate` 전에. 아이콘이 원형이 아니면 `Theme.SplashScreen.IconBackground` + 36dp.

### Q2. 아이콘 버튼 크기와 터치 영역 — 심각도 중하

- 위치: `SessionList.kt:81` (40dp), `:181-187`; `Ask.kt:95` (40dp), `:376-399`; `SessionDetail.kt:126` (44dp), `:464,473`.
- 가이드:
  - 품질 기준 WO-V2 "최소 48x48dp 터치 영역". 접근성 문서는 "작은 화면이라 40dp도 허용되는 경우가 있다". https://developer.android.com/training/wearables/accessibility
  - 버튼 가이드의 표준 크기: Large 60, Default 52, Small 48, Extra Small 32dp(32dp는 주변 여백으로 48dp 확보). https://developer.android.com/design/ui/wear/guides/components/buttons
  - 라이브러리 소스 주석: "IconButton 크기는 `Modifier.touchTargetAwareSize`로 정해서 최소 터치 영역을 보장하라". (`touchTargetAwareSize`는 48dp가 되도록 바깥 여백을 붙임.)
- 지금 앱: `Modifier.size(40.dp/44.dp)`. 표준 크기가 아니고, 터치 영역 보장은 Compose의 암묵적 확장에 기대는 상태(확실한지는 문서로 확인 못 함).
- 고칠 방법:
  - 목록 위 3개(SessionList): `Modifier.touchTargetAwareSize(IconButtonDefaults.SmallButtonSize)`(48dp). 48×3 + 8×2 = 160dp라 들어감.
  - 세션 화면 아래 2개(SessionDetail): 48dp(Small) 또는 52dp(Default)로. 아래 둥근 테두리 안에 들어가는지 작은 화면(192dp) 미리보기로 확인.
  - 답변 아래 4개(Ask): 48dp×4 + 간격 8dp×3 = 216dp라 목록 폭(226dp에서 ScreenScaffold 양옆 여백을 뺀 값, 정확한 값은 확인 못 함)에 안 들어갈 가능성이 큼. 지금 40dp 줄(184dp)도 거의 꽉 참. 그래서 둘 중 하나: (a) 보이는 크기는 40dp(문서상 허용 예외)로 두고 `touchTargetAwareSize(40.dp)`로 바꿔 터치 영역 48dp를 명시, 간격은 0-4dp로 줄여 폭 유지, (b) 2개씩 2줄로 48dp.
  - 아이콘 크기는 계속 `IconButtonDefaults.iconSizeFor(...)`.

### Q3. 클릭 없는 카드 — 심각도 중하 (접근성)

- 위치: `Ask.kt:436-437` (`Card(onClick = {})`), `Usage.kt:212-217` (배경+패딩으로 카드 흉내, 주석 "a Card is always clickable").
- 가이드/사실: Wear Compose M3 1.7.0에는 `onClick` 없는 `Card`/`TitleCard`/`AppCard`/`OutlinedCard` 오버로드가 있음(`NonClickableCardKt`, 1.7.0 AAR에서 확인; `transformation: SurfaceTransformation?` 인자도 있음 — androidx-main API 목록 기준).
- 지금 앱: 답변 카드는 눌러도 아무 일 없는데 TalkBack은 "두 번 탭하여 활성화"라고 읽을 수 있음. 사용량 카드는 카드 모양을 직접 그림.
- 고칠 방법: 두 곳 모두 `Card(modifier = ..., transformation = SurfaceTransformation(spec)) { ... }`(onClick 없는 버전). Usage 주석도 사실에 맞게 정리.

### Q4. TalkBack이 건너뛸 수 있는 낮은 줄 — 심각도 중 (접근성)

- 위치: `Common.kt:544` (`CaptionText`, labelSmall 한 줄), 사용처 `SessionDetail.kt:405-430`(working 12dp 스피너, block, outcome), `Ask.kt:452`(걸린 시간), `Settings.kt:313-316`(버전, 개인정보).
- 가이드: "TalkBack은 세로 목록에서 높이 32dp 미만 항목이나 화면 끝에 걸친 항목을 읽지 않는다. 항목은 최소 32dp". https://developer.android.com/training/wearables/accessibility
- 지금 앱: 한 줄 캡션은 약 16-18dp, 작업 중 표시는 12dp. "보냄", "보내기 실패" 같은 결과 메시지도 이런 줄이라 TalkBack 사용자가 놓칠 수 있음. 앱 전체에 `liveRegion` 없음.
- 고칠 방법: 목록에 들어가는 캡션 항목에 `Modifier.heightIn(min = 32.dp)`(가운데 정렬 유지). 결과(outcome)·오류 줄에는 `semantics { liveRegion = LiveRegionMode.Polite }`로 자동 낭독.

### Q5. 크라운 스냅과 손가락 스크롤 스냅 불일치 — 심각도 하

- 위치: `Onboarding.kt:71,122,215`, `Settings.kt:184`, `Request.kt:343` (크라운만 `RotaryScrollableDefaults.snapBehavior`).
- 가이드: "스냅을 쓸 때는 `flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(state)`와 `rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(state)`를 같이 줘야 일관된다". https://developer.android.com/training/wearables/compose/lists?version=3
- 지금 앱: SessionList, AskHistory, Usage는 둘 다 줌(좋음). 위 화면들은 크라운만 스냅.
- 고칠 방법: 같은 화면에 `flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(listState)` 추가. 긴 글 화면(권한 요청 본문, Ask 답변, 세션 대화)은 지금처럼 기본(스냅 없음) 유지가 맞음.

### Q6. 10sp 글자 — 심각도 하

- 위치: `SessionDetail.kt:808` (펼친 도구 상세, `bodyExtraSmall` = 10sp), `Usage.kt:271` (남은 시간, 10sp).
- 가이드: WO-V14 "필수 글자는 최소 12sp, 비필수는 10sp". https://developer.android.com/docs/quality-guidelines/wear-app-quality
- 지금 앱: 라이브러리 값 확인 — bodyExtraSmall 10sp, bodySmall 12sp, labelSmall 13sp. 도구 상세는 사용자가 일부러 펼쳐 읽는 내용이라 필수에 가까움. 남은 시간은 보조 줄이라 10sp 허용 범위.
- 고칠 방법: 도구 상세만 `bodySmall`(12sp). 남은 시간은 그대로 둬도 됨.
- 참고: 계정 표시 글자(`SessionList.kt:95`, 7dp)와 제공자 배지 글자(배지 14dp의 75%)는 dp 고정이라 글꼴 크기 설정을 안 따름. 장식 성격이고 TalkBack 설명이 있어 하.

### Q7. 확인창 표시 시간 — 심각도 하

- 위치: `Request.kt:81` (1,200ms), `:195,203`.
- 사실: 라이브러리 기본값 `ConfirmationDialogDefaults.DurationMillis = 4000L`, 접근성 설정 시 자동 연장(소스 확인).
- 고칠 방법: 기본값 사용 또는 2,000ms 이상. 탭하면 바로 닫히는 기능은 이미 있어 길게 해도 불편 적음.

### Q8. 확인창의 긴 초안 정렬 — 심각도 하

- 위치: `Ask.kt:179` (`TextAlign.Center`), `SessionDetail.kt:261` (기본 가운데 정렬).
- 가이드: "내용이 3줄을 넘으면 왼쪽 정렬로 읽기 쉽게, 아니면 가운데". https://developer.android.com/design/ui/wear/guides/components/dialogs
- 고칠 방법: `onTextLayout`으로 줄 수를 보고 3줄 넘으면 `TextAlign.Start`, 또는 글자 수 기준으로 단순 분기.

### Q9. 진동(햅틱) — 심각도 하

- 위치: `data/Haptics.kt:113` (`touch()`: USAGE_TOUCH로 직접 조합한 진동), 호출부 `SessionDetail.kt`(보냄), `Request.kt`(거부·오류), `Ask.kt:174`(제공자 전환).
- 가이드: "동작이 미리 정의된 상수(CONFIRM, REJECT 등)에 해당하면 그 상수를 쓰라. 기기별로 맞춰진 일관된 느낌을 준다". https://developer.android.com/develop/ui/views/haptics/haptics-principles
- 사실: Compose UI 1.12.1에 `HapticFeedbackType.Confirm/Reject/SegmentTick/ToggleOn/ToggleOff` 있음(캐시 AAR 확인). M3 `SuccessConfirmationDialog`도 내부에서 `Confirm`을 씀.
- 고칠 방법: 화면에서 손으로 한 동작(보냄 CONFIRM, 거부 REJECT, 전환 SEGMENT)은 `LocalHapticFeedback.current.performHapticFeedback(...)`. 요청 도착·작업 완료 같은 "이벤트" 진동(USAGE_NOTIFICATION, 백그라운드 수신기)은 지금 방식 유지.

### Q10. 읽어주기(TTS)와 Android 17 오디오 제한 — 심각도 중하

- 위치: `Reader.kt:127-131` (화면이 구성에서 빠질 때만 정지).
- 가이드: Android 17은 "화면이 꺼졌거나 액티비티가 안 보이면" 오디오 재생을 조용히 막음(모든 앱). https://developer.android.com/about/versions/17/changes/bg-audio
- 지금 앱: 홈으로 나가도 Ask 화면은 뒤 스택에 남아 TTS가 계속 말할 수 있음. TTS는 엔진 프로세스가 재생해서 이 제한에 걸리는지는 문서에 없음(확인 못 함).
- 고칠 방법: `LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reader.stop() }` 추가, N1 이후엔 앰비언트 진입 때도 정지. 테스트: `adb shell cmd audio set-enable-hardening throw`.

### N3. 페어링 코드 피커 — 심각도 중 (접근성)

- 위치: `Onboarding.kt:357-371` (PickerGroup, 6칸, 칸마다 30dp 너비).
- 가이드: 터치 영역 48dp(작은 화면 예외 40dp). https://developer.android.com/training/wearables/accessibility
- 지금 앱: 칸을 탭해서 고르는 영역이 30dp라 기준 미달. 크라운으로 숫자를 돌리는 건 문제없음.
- 고칠 방법(택1):
  - 2자리씩 3칸(00-99) 피커 → 칸 너비 약 60dp. 단점: 한 칸에 100개라 크라운으로 돌리는 양이 늘어남.
  - 숫자 키패드 화면(3×4 버튼, 48dp)으로 교체.
  - 시스템 키보드 입력(이미 있는 `rememberTextInput`)을 주 경로로, 피커는 보조.

### N4. 대화 화면에 `reverseLayout` — 심각도 중

- 위치: `SessionDetail.kt:310-325` (맨 아래 따라가기: `atBottom` + `scrollToItem(lastIndex, screenHeight)`), `:353` (TLC).
- 가이드: "메시지 앱·실시간 로그처럼 최신 내용을 보고 싶을 때 `reverseLayout = true`. 목록이 아래 끝에 붙는다". https://developer.android.com/training/wearables/compose/lists?version=3
- 지금 앱: 위에서 아래로 쌓고, 새 항목이 오면 화면 높이만큼 더 내려 끝에 맞추는 수동 처리.
- 고칠 방법: 항목 순서를 뒤집어 넣고 `reverseLayout = true`. "이전 불러오기" 버튼은 목록의 마지막(화면 위쪽)으로. 따라가기 로직 대부분이 필요 없어짐. 펼친 긴 메시지, 게이지 표시 조건과 같이 테스트 필요(중간 크기 작업).

### N5. 다이내믹 컬러 — 심각도 중하 (선택)

- 위치: `Theme.kt:30-65` (고정 `WristlineColors`), `:91`.
- 가이드: M3 이전 가이드가 `dynamicColorScheme(LocalContext.current) ?: myBrandColors`를 권장 패턴으로 제시. https://developer.android.com/training/wearables/compose/migrate-to-material3 , 색 시스템에 "시스템/시계 화면 기반 다이내믹 컬러" 포함. https://developer.android.com/design/ui/wear/guides/styles/color
- 사실: `dynamicColorScheme()`은 API 35+이면서 기기 전역 설정(dynamic theming)이 켜져 있을 때만 값을 주고, 아니면 null(소스 확인). Galaxy(One UI Watch)가 이 설정을 켜는지는 확인 못 함.
- 지금 앱: 브랜드 하늘색 고정. 상태색(초록·노랑·회색)과 허용 초록은 이미 테마 밖 상수라 다이내믹을 켜도 의미가 안 바뀜(좋은 구조).
- 고칠 방법: 설정에 "시계 화면 색 따르기"(기본 끔) 추가 → 켜면 `dynamicColorScheme(context) ?: WristlineColors`. tertiary를 "주의 노랑"으로 쓰는 곳(`SessionList.kt` 요청 버튼, `SessionDetail.kt` Respond)은 다이내믹에서 노랑이 아닐 수 있으니 `Status.Attention`을 직접 쓰도록 먼저 분리.

### N6. 큰 글꼴에서 사용량 표 축소 — 심각도 중하

- 위치: `SessionList.kt:365` (표가 카드보다 넓으면 통째로 축소).
- 가이드: WO-V1 "사용자 글꼴 크기를 따르고, 겹치거나 잘리지 않게", WO-V14 최소 12sp. https://developer.android.com/docs/quality-guidelines/wear-app-quality
- 지금 앱: 글꼴을 키우면 표를 줄여서 결국 글자가 다시 작아짐(사용자 설정을 되돌리는 효과).
- 고칠 방법: 넓이가 모자라면 시계 칸을 다음 줄로 내리는 2줄 배치로 전환. 축소는 마지막 수단으로만.

### N7. targetSdk 37 준비 — 심각도 중 (계획)

- 위치: `app/build.gradle.kts:24` (`targetSdk = 36`).
- 판단: 지금 36 유지가 맞음(Play 기준 충족). 37로 올릴 때 확인할 것:
  - 로컬 네트워크: 사용자가 브리지 주소로 LAN IP(예: 192.168.x.x)나 `.local`을 넣으면 권한 없이 연결이 타임아웃 남. Tailscale Funnel(공개 HTTPS)이면 해당 없음으로 보이나, tailnet 주소(100.64.0.0/10)가 "로컬"로 분류되는지는 문서에 없음. 방법: 주소가 사설 대역이면 `ACCESS_LOCAL_NETWORK` 요청, 아니면 요청 안 함. https://developer.android.com/privacy-and-security/local-network-permission
  - 오디오: Q10 테스트.
  - 그 밖의 Android 17 변경(인증서 투명성 기본, ECH, 리플렉션 제한 등)은 이 앱 구조상 영향 적어 보임(라이브러리 내부는 확인 못 함).

### N8. 세션 화면 시계 숨김 — 심각도 하 (디자인 결정)

- 위치: `SessionDetail.kt:344` (`timeText = {}`).
- 가이드: (M2.5 세대 문서) "앱의 모든 화면 위에 시간을 보여주길 권장하고, 스크롤하면 사라지게". https://developer.android.com/design/ui/wear/guides/m2-5/components/time-text . 현재 품질 기준 목록엔 시간 표시 의무가 없음(WO-V7/V11은 "더 이상 요구 아님").
- 고칠 방법: 기본 TimeText를 살리면 M3 ScreenScaffold가 스크롤 때 알아서 숨김. 상단 공간이 아쉬우면 지금처럼 숨겨도 기준 위반은 아님.
- 참고: androidx-main(미출시)에는 ScreenScaffold `timeText`가 "시스템 상태 표시줄 오버레이"와 연동되는 설명이 생김. 다음 버전에서 동작이 바뀔 수 있으니 업그레이드 때 다시 볼 것(1.7.0에는 없음).

### N9. 토스트 — 심각도 하 (공식 근거 약함)

- 위치: `Ask.kt:144,200,251`, `AskHistory.kt:82`, `Common.kt:479`, `Reader.kt:80,112,117`.
- 가이드: M3에 `FailureConfirmationDialog`가 있고, 다이얼로그 가이드는 "행동 결과는 가능하면 화면 변화로 보여주라"고 함. 다만 "Wear에서 토스트를 쓰지 말라"는 명시 문구는 찾지 못함.
- 고칠 방법: 오류는 `FailureConfirmationDialog`(짧은 곡선 글자) 또는 목록 안 오류 줄(이미 쓰는 `CaptionText` 패턴)로 통일. 서두를 일 아님.

### 기타 관찰 (낮음)

- 토큰 직접 입력(`Onboarding.kt:182`, `Settings.kt` token): 품질 기준 WO-P6은 "시계에서 아이디·비밀번호를 직접 입력하게 하지 말라". 토큰은 비밀번호는 아니지만 성격이 비슷. 6자리 페어링 코드가 주 경로이니 토큰은 "고급" 뒤로 두는 정도면 충분.
- 알림 액션 수신기(`NotificationActionReceiver`): 공식 문서의 "BroadcastReceiver를 알림 액션 대상으로 못 쓴다"는 문장은 "휴대폰 앱 열기" 맥락. 이 앱은 수신기에서 화면을 띄우지 않으므로 해당 없음.
- Ongoing Activity: 정적 아이콘만 있음(문서상 애니메이션 아이콘이 없으면 정적 아이콘으로 대체되므로 허용).
- 요청 화면 `AnimatedContent`(`Request.kt:323`, `Onboarding.kt:239`)는 기본 전환을 씀. 테마의 `MaterialTheme.motionScheme` 스펙으로 맞추면 다른 화면과 움직임이 통일됨.
- 카드 누름 축소(`Common.kt:105,127`)는 라이브러리에 없는 커스텀 모션. "라이브러리 모션만" 방침과 맞출지 결정만 하면 됨(기능 문제 아님).
- 세션 목록의 첫 항목(아이콘 줄, `SessionList.kt:172`)에는 `minimumVerticalContentPadding(IconButtonDefaults.minimumVerticalListContentPadding)`, 마지막 세션 카드에는 `CardDefaults.minimumVerticalListContentPadding`를 주면 다른 화면과 여백 규칙이 같아짐. Ask 답변 아래 아이콘 줄(`Ask.kt:364`)은 `ButtonDefaults` 대신 `IconButtonDefaults` 값이 맞음.

---

## 4. 이미 잘 맞춘 부분 (유지)

- M3 Expressive 부품만 사용, M2.5 코드 없음(단 내비 라이브러리가 M2.5를 간접으로 끌어옴 → L1).
- AppScaffold + 화면마다 ScreenScaffold, 스크롤 상태 공유로 스크롤 표시기 자동(WO-V8).
- TransformingLazyColumn + `rememberTransformationSpec` + `SurfaceTransformation` + `transformedHeight` 순서까지 가이드대로.
- `minimumVerticalContentPadding`(1.6 이름)과 `ListHeaderDefaults/ButtonDefaults/TextDefaults` 최소 여백 사용.
- ListHeader(제목 의미 자동 부여), EdgeButton(온보딩, 다중 선택 질문), ConfirmationDialog, AlertDialog.
- 아이콘 버튼 모양 변형(`IconButtonDefaults.animatedShapes`) = Expressive 모양 모핑.
- 줄이기 모션(`LocalReduceMotion`) 존중, 검은 배경(WO-V13), 스와이프로 닫기(WO-V3, SwipeDismissableNavHost).
- TalkBack 설명을 현지어로 풀어 씀, 큰 글꼴/작은 화면 미리보기 있음.
- 알림: 채널 3개 분리, 큰 글 스타일, 잠금 시 인증 요구 액션, Ongoing Activity(WO-V4), FGS 유형 선언 및 전경에서만 시작.
- 라이브러리 전부 최신 안정판(아래 표).

---

## 5. 라이브러리 버전 / SDK

| 항목 | 현재 | 최신 안정 (2026-10-01) | 메모 |
|---|---|---|---|
| AGP | 9.4.1 | 9.4.1 | 9.5.0-alpha07 있음 |
| Kotlin | 2.4.20 | 2.4.20 | |
| Compose BOM | 2026.09.00 | 2026.09.00 | Compose UI 1.12.1 해석 |
| Wear Compose (material3/foundation/navigation) | 1.7.0 | 1.7.0 (2026-09-23) | |
| activity-compose | 1.13.0 | 1.13.0 | 1.14.0-alpha03 |
| wear-ongoing | 1.1.0 | 1.1.0 | |
| wear-input | 1.2.0 | 1.2.0 | |
| wear-tooling-preview | 1.0.0 | 1.0.0 | |
| okhttp / kotlinx-serialization | 5.5.0 / 1.11.0 | 같음 | |
| androidx.core (간접) | 1.18.0 | 1.19.1 | Live Updates용 새 NotificationCompat API를 쓸 때만 직접 선언 고려 |
| core-splashscreen | 없음 | 1.2.0 | Q1 |
| compose-navigation3 | 없음 | 1.7.0 | L1. M3 + navigation3 1.0.0만 의존 |
| tiles / glance-wear / remote compose | 없음 | 1.6.2 / 1.0.0-alpha19 / 1.0.0-alpha20 | L2 |

- 간접 의존: `compose-navigation 1.7.0` → `compose-material`(M2.5) 1.7.0 + `navigation-compose 2.6.0`. R8이 안 쓰는 부분은 지우지만, M3 전용으로 가려면 Navigation 3.
- 폐지(deprecated) API 사용: 찾지 못함. 린트 결과도 Wear/폐지 경고 없음(UseKtx 8, PluralsCandidate 3, TypographyDashes 2만). `ButtonDefaults.CompactButton*` 상수, `SwipeDismissableNavHost`의 폐지 오버로드는 앱이 안 씀.
- SDK: `compileSdk 37`(Wear OS 7 API) + `targetSdk 36`. Play 기준(Wear는 API 35+) 충족. Wear OS 7 설정 가이드는 테스트용으로 37을 권장. `minSdk 33`.
- 64비트 요구(2026-09-15): 네이티브 코드 없음 → 해당 없음.

---

## 6. 공식 문서로 확인하지 못한 것

- Galaxy(One UI 9 Watch)에서 `dynamicColorScheme()`이 null이 아닌지(기기 전역 설정 지원 여부).
- Galaxy에서 앰비언트 진입 때 SCREEN_OFF 방송이 항상 오는지, Ongoing Activity가 실제로 시계 화면 복귀를 막는지(N2).
- Tailscale tailnet 주소(100.64.0.0/10)가 Android 17 "로컬 네트워크"로 분류되는지.
- TTS(엔진 프로세스 재생)가 Android 17 백그라운드 오디오 제한에 걸리는지.
- ScreenScaffold 기본 양옆 여백의 정확한 dp(Ask 4버튼 줄이 48dp로 들어가는지 판단용).
- `Modifier.size(40.dp)` 아이콘 버튼의 실제 터치 영역이 48dp로 확장되는지(Compose 히트 테스트 확장에 기대는 부분). 문서는 `touchTargetAwareSize`를 쓰라고만 함.
- "Wear에서 토스트 비권장"을 말하는 M3 공식 문구.
- Wear OS 7의 알림 개선(Live Updates 외) — 기능 페이지에 다른 항목 없음.
- Samsung 자체 제스처(더블 핀치)가 Wear 한 손 제스처 API와 연결되는지 — 공식 문서는 Pixel 전용이라고만 함.
- `transformation` 인자가 1.7.0의 클릭 없는 Card에 있는지는 androidx-main API 목록으로 봤음(1.7.0 AAR엔 클래스 존재만 확인). IDE에서 한 번 확인.

---

## 7. 다음 단계 제안

1. N1(앰비언트)부터. 화면 꺼짐 방송 우회를 공식 API로 바꾸면 배터리·번인·N2 판단이 한 번에 정리됨.
2. 같은 날 실기기로 N2 재현: 모니터링 켬 → 앱 열어둠 → 손목 내림 → 설정한 시간 뒤 시계 화면으로 돌아오는지.
3. Q1-Q10은 서로 독립이라 한 커밋씩 바로 가능. 각각 미리보기 + TalkBack 한 번씩 확인.
