# Now Bar stack: monitoring card vs Live Update (stack35, 2026-10-02)

## 요약 (3줄)
- Now Bar 카드 순서는 "카테고리 점수 → 가장 최근에 (다시) 올라온 카드" 순서다. 모니터링 카드가 Live Update보다 늦게 다시 올라오면 맨 위로 간다. 카드 내용과는 상관없다.
- Live Update 카드가 보여주는 건 `shortCriticalText`(▶, 3/7)와 제목, 이 두 줄뿐이다. 진행 막대, 경과 시간(위로 세는 크로노미터), `android.text`는 그려지지 않는다.
- 아이콘은 **small icon 비트맵**을 색칠 없이 그대로 쓰고, 그 뒤에 `setColor`로 색칠된 원을 깐다. "아이콘만" 스타일은 앱(패키지)마다 하나라서 Wristline 카드 전부에 적용된다.

## 1. 기기 캡처 (watch-shots/)
| 파일 | 내용 |
|---|---|
| stack35-face.png (17:49) | 칩 = Wristline 모니터 아이콘 (흰 스퀴클, 남색 다이얼) + `◦`. 아래에 카드 1장이 더 쌓여 있음. 카드는 2장: 모니터(17:44:05 게시)와 "Submodule 업데이트" Live Update(17:43:23 게시) |
| stack35-face-pretap.png (17:50) | 칩 = 파란 원 안의 흰 단색 마크 + `▶`. 17:49:33에 새 Live Update("wristline")가 올라와 맨 위로 감. 접근성 설명 "Wristline 외 앱 2개 실행 중", 카드 3장 |
| stack35-expanded-1.png | 펼친 목록 (위에서부터): ① 파란 원 아이콘 / `▶` / `wristline` ② 모니터 아이콘 / `◦` / `Wristline` ③ 파란 원 / `▶` / `Submodule 업데이트`. 진행 막대·시간·텍스트 "▶ running"은 없음 |
| stack35-expanded-2.png | 스와이프 뒤에도 같은 화면 (세 번째 카드는 반쯤 잘림, 목록은 3장뿐) |
| stack35-expanded-3.png | 화면이 꺼지며 펼친 화면 닫힘 → 앰비언트 시계, 칩은 아이콘만 (파란 원 Live Update 아이콘) |
| stack35-notif*.txt, stack35-logcat.txt, *.xml | dumpsys / logcat / uiautomator 원본 |

17:52:58에 "wristline" Live Update가 취소됨 (`removeNowBar`). 지금은 카드 2장이다.
dumpsys 값: Live Update는 `PROMOTED_ONGOING`, ProgressStyle 진행률 미정(indeterminate), `shortCriticalText=▶`, `text=▶ running`, `showChronometer=true`, `when`은 과거 시각, `category=progress`.
모니터: `category=service`, `customDisplayBundle` 있음, `text=◦`.

## 2. Samsung 코드 (SecClockworkSysUi 디컴파일본, nowbar/src/sources)
### (a) 맨 위 카드 정하는 규칙
- `wnotification/nowbar/k.java:39,54` `getSortedNowBarDataList()` = `sortedWith(compareBy{priority}.thenByDescending{appAccessTime})`. 첫 번째 카드가 칩이 되고, 시계 화면에는 최대 3장이 쌓인다 (`wj/a.java` MAX_NOWBAR_ITEM_ON_OVERLAY=3).
- priority는 카테고리 점수다. `nowbar/common/b.java:21-42` call=0, siren=1, **workout=2**, compass=2, 그 밖은 전부 3 (navigation·stopwatch·alarm·progress·service 포함).
- appAccessTime은 카드 객체가 만들어질 때 "지금"으로 정해진다 (`wh/a.java:72`). 알림을 다시 게시할 때마다 새 객체가 만들어지므로 (`rg/c.convertNowBarData` → 저장소 `k.add`가 같은 id 자리를 교체), **가장 최근에 (다시) 게시된 카드가 맨 위**다. 그 앱의 화면이 앞으로 나오거나 (`h.onTaskFocusChanged` → `updateAccessTime(pkg)`: 그 패키지 카드 전부) 펼친 목록에서 그 카드를 누를 때도 갱신된다.
- 카드 종류 (LIVE_UPDATE / CUSTOM / OA) 자체로는 순서가 바뀌지 않는다. 사용자 고정(pin) 기능도 없다.
- 종류 판정 (`mainui/module/wnotification/common/m.java getNowBarSourceType`): OngoingActivity 데이터 있음 → ONGOING_ACTIVITY, promoted → LIVE_UPDATE, `customDisplayBundle.enableNowBar && nowBarData` → CUSTOM_NOWBAR, 그 밖은 일반 알림.

### (b) 펼친 카드가 그리는 것
- LIVE_UPDATE (`wh/a.fillLiveUpdateContents`, 115-139): 후보를 순서대로 모은다. [shortCriticalText | (텍스트가 비어 있고 when이 1분 이상 미래일 때 상대 시간) | (when이 미래인 카운트다운 크로노미터)] → title → appName → 패키지 이름. 첫 번째 = 칩 글자 = 펼친 화면 첫 줄, 두 번째 = 둘째 줄.
  - `wh/a.java:96`: `(when-now)/60000 <= 0`이면 크로노미터를 붙이지 않는다. 그래서 **위로 세는 경과 시간은 절대 표시되지 않는다.**
  - `android.text`, subText, ProgressStyle 구간·진행률은 Now Bar 어디에서도 읽지 않는다 (nowbar 패키지에 progress 사용처 없음).
- CUSTOM_NOWBAR (`data/noti/g.fillNowBarExtraData`): cardContents(칩), expandPrimaryInfo, expandSecondaryInfo, expandViewIcon(+Bg), cardChronometerRemoteView, expandChronometerRemoteView + expandChronometerPosition(1 = [시계/둘째 줄], 2 = [첫 줄/시계]), cardContentsAmbient, cardColorStart/End, shouldHide.
- 펼친 항목 (`ak/a.createExpandData`, `expand/ui/composable/item/f,h,i`): 아이콘 + 한 줄짜리 텍스트 두 개 (maxLines=1, 말줄임표). 둘째 줄이 비면 "간결형" (아이콘 + 한 줄). 화면에서 둘째 줄에 들어가는 글자는 대략 라틴 15자, 한글 9~10자.

### (c) "아이콘만" 스타일
- `wj/a.java:53` `getTopItemNowBarStyle(list.get(0))` → `common/f.getNowBarAppStyle(packageName)`. 스타일 값은 패키지마다 하나 (isIconText)이고 맨 위 카드의 패키지 것을 쓴다. 카드마다 따로 정하는 키는 없다.
- 그래서 "아이콘만"이면 Wristline 카드는 어느 것이 맨 위에 있든 글자가 안 보인다. 남는 건 아이콘뿐이다.

### (d) Live Update 아이콘 (코디네이터 질문)
- `data/noti/g.java:409-424 fillNowBarLiveUpdateData`: `getLiveNotificationIconInfo().createSmallIconInfo()`. live일 때 (`l.java:275`) 이 값은 `showAppIcon=false`라 **알림의 small icon 비트맵**이다. 이 비트맵만 꺼내 (`Icon.createWithBitmap`) cardIconLeft / expandViewIcon / queIcon에 넣는다. 전경 색칠은 버린다 (`NowBarIcon.setIcon` → `clearColorFilter`). 즉 **원본 색 그대로** 그린다.
- 배경: `nowbar_card_view_liveupdate_icon_bg` = 타원(oval)을 appColor로 칠한 것. appColor = `setColor`를 대비 보정한 색 (`common/u.getAppColor`). #4FA8FF가 #0077C7이 된 것을 화면에서 확인했다. 전경은 칩에서 2dp, 펼친 화면에서 4dp 안쪽으로 들어간다. **이 원은 끌 수 없다.** 원 테두리가 반드시 조금 보인다.
- **largeIcon은 Now Bar Live Update 카드에서 쓰이지 않는다** (createLargeSubIconInfo만, 사용처 없음).
- 지금 small icon = `R.drawable.ic_notification` (흰 단색 마크) → 파란 원 위의 흰 마크로 보인다 (사용자가 싫어하는 모양).
- 플랫폼 문서 (live-update 페이지): small icon은 "칩에 항상 표시"된다고만 하고, 단색이어야 한다는 요구는 이 페이지에 없다. 일반 알림 지침 (상태 표시줄 small icon = 알파 마스크)은 폰 상태 표시줄 이야기이고, 이 워치 SysUI는 색이 있는 비트맵을 그대로 그린다 (`isGrayscaleIcon`이 false면 색칠 안 함). 승격(promote) 조건 (ongoing, title, 커스텀 뷰 없음, 그룹 요약 아님, colorized 아님, MIN 중요도 아님, 허용된 스타일)에도 아이콘 조건은 없다.
- 해야 할 일: `NotificationCompat.Builder.setSmallIcon(IconCompat.createWithBitmap(nowBarIcon(waiting)))`. 모니터 카드와 같은 배지 붙은 ic_ongoing 비트맵이다. "아이콘만" 스타일에서도 ✋ 배지가 보이게 된다. 원 테두리 색은 `setColor`로 정한다. 대비 보정 때문에 밝기가 약 0.17로 고정되니 색조만 고를 수 있다. 다이얼 남색 계열이면 테두리처럼 보일 것이다 (기기 확인 필요).

### (e) 모니터 카드를 빼면?
- bundle 없이 다시 게시하면 → 종류 null → 일반 알림이 된다 (OngoingActivity 데이터가 없으니 OA 카드로 바뀌지 않는다). **하지만** `c0.addStreamItem`(68행)은 promoted일 때만 Now Bar 카드를 지운다. `shouldHide=true`도 75행에서 그냥 return한다. 그래서 **같은 id로 다시 게시하면 예전 카드가 남을 가능성이 크다** (코드로 추론한 것, 기기 미검증). 깔끔하게 빼려면 알림을 지워야 한다. 예: `startForeground(다른 ID, 일반 알림)`으로 ID를 바꾸면 AOSP가 이전 ID 알림을 취소한다. 대신 일반 알림이 된 FGS 알림이 알림 목록에 한 줄 생긴다.

## 3. 결정할 것 (질문 → 추천)
**Q1. Live Update가 있으면 그게 맨 위에 와야 하나?** → 예. 세션별 정보가 더 구체적이기 때문이다.
- 안 1 (추천): Samsung Now Bar가 있는 기기 (`hasSamsungNowBar()`)에서만 Live Update의 category를 `"workout"`(=2)으로 둔다. 모니터(service=3)보다 항상 위에 온다. 게시 시각 경쟁도 없다. 이 SysUI에서 category를 읽는 곳은 Now Bar 순위표뿐이다 (그 밖의 사용처 없음 확인). 단점은 의미상 틀린 카테고리를 쓴다는 것. 그래서 Samsung 기기로만 한정한다.
- 안 2: 모니터 카드를 다시 게시한 뒤 Live Update를 다시 게시 ("나중에 온 게 위"). 비공식 API를 쓰지 않는다. 단점: 비동기 아이콘 로딩 때문에 순서가 엎치락뒤치락할 수 있고, 게시 횟수가 늘어난다.
- 안 3: Live Update가 있는 동안 모니터 카드를 없앤다 (FGS 알림 ID 바꾸기). 칩에는 Live Update만 남는다. 단점: 일반 알림 한 줄이 생기고, 세션 여럿의 "✋ N" 합계 배지가 사라지고, ID 전환 코드가 복잡해진다.

**Q2. 펼친 화면 정보량** → 칸은 줄 두 개뿐이다. 첫 줄 = shortCriticalText(칩 글자, ≤7자 권장), 둘째 줄 = title. 그래서 shortCriticalText는 `▶ 3/7` / `✋` / `▶ 12m`처럼, title은 "세션 이름"만 짧게 쓴다. 경과 시간은 크로노미터로 안 나오니 shortCriticalText에 분 단위 텍스트를 넣고 1분마다 갱신해야 한다 (배터리와 맞바꾸는 것. 갱신하지 않는 안이면 `3/7`만).

**Q3. 아이콘** → small icon을 배지 붙은 ic_ongoing 비트맵으로 바꾼다 (위 2(d)). 아이콘만 스타일에서도 상태(✋)가 보인다. 원 테두리는 남는다.

**바꿀 숫자/값**: (1) Live Update category: `progress` → Samsung에서만 `"workout"`. (2) small icon: `R.drawable.ic_notification` → `IconCompat.createWithBitmap(nowBarIcon(waiting))`. (3) `setColor` 색조 (지금 #4FA8FF → 남색 계열?). (4) shortCriticalText 형식 (`▶`→`▶ 3/7`·`▶ 12m`).

## 4. 미확인 / 다음 단계
- 안 1의 순서, 비트맵 small icon 렌더링, 테두리 색: 빌드를 설치해 기기에서 확인해야 한다 (이번 작업은 읽기만 했다).
- bundle 없이 다시 게시할 때 남는 예전 카드 (2(e)): 안 3을 고를 때만 확인하면 된다.
