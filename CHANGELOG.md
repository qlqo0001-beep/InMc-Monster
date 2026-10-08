# inmc-monster 변경 기록

---

## 미배포 — 검증기 `/몹 검증`(2026-10-08)

- `/몹 검증` — 서버 안 자동 검증(2026-10-08, 드랍·상점 검증기와 같은 틀). 결과는 채팅 + `plugins/<플러그인>/verify/` 파일. 정의 · 스킬 레지스트리(정의가 쓰는 스킬 id 가 전부 등록됐는지) · 머리 위 소환(드랍 없음·예산 제외·규칙 무시, 태그 `zz_verify`)→태그로 세기→정리 ·
  수식어 배율 계산 · 월드 설정 · 관리 화면. 스킬은 쏘지 않는다(`/몹 스킬테스트` 로).

## 미배포 — 긴 목록은 고르는 화면으로 · 값 입력은 입력창(2026-10-08)

- 스킬 발동 조건(9)·트리거 발동 종류(13)와 보기가 7개 이상인 열거 인자(스킬·행동)가 좌/우클릭 순환 대신 **고르는 화면**(core `PickMenu`)을 연다.
- 값 입력 65곳이 core `ChatPrompt` 를 거쳐 전부 **입력창**이 됐다.

## 미배포 — 켤 때 "미설치" 석 줄을 한 줄로(2026-10-08)

- MythicLib·MythicMobs·MagicSpells 이 없으면 훅마다 한 줄씩 찍던 것을 `Monsters.setupIntegrations` 가 "선택 연동 없음: …" 한 줄로 모은다.
  늦게 켜진 플러그인 때문에 다시 돌 때(`refreshIntegrations`) 같은 말은 반복하지 않는다. MMOItems 미설치는 core 가 한 번만 알린다.

## 미배포 — 커스텀 몹이 플레이어를 치면 서버가 죽던 것(2026-10-08)

- **MythicLib 이 없는 서버**에서 커스텀 몹의 근접·스킬 피해가 끝없이 돌아 서버가 `StackOverflowError` 로 내려갔다(던전 시험 중 테섭에서).
  `DamageBridge.apply` 가 `target.damage(amount, mob)` 로 넣으면 그 호출이 `EntityDamageByEntityEvent` 를 새로 쏘고, `CombatListener.onDamageByEntity`
  가 그것을 또 "우리 몹의 공격"으로 받아 `apply` 를 다시 불렀다. 재진입 막기(`hasRegisteredAttack`)가 MythicLib 경로에만 있었다.
- 고침: `DamageBridge.isApplying()` — 우리 `damage()` 호출(근접·스킬·가시 반사) 동안 서는 표시. 리스너는 그 사건에 손대지 않는다.
  모든 피해가 `DamageBridge` 한 곳을 지나므로(스킬은 `Act.damage → applySkill`) 이것으로 막힌다. 테스트 153 통과, 테섭에서 몹에게 맞아도 그대로 진행 확인.

## 미배포 — 드랍 이벤트 배율 연동·방송 닉네임 표시

- 테섭 "드랍 2배가 몬스터에도 같이" — `death/DropService.roll` 의 확률 배율에 inmc-drops 이벤트 배율을 곱한다
  (`integration/DropsBoostHook`, 리플렉션 — 컴파일 의존 없음). 어픽스 추가드랍도 같은 roll 이라 함께 오른다.
  drops 없음·꺼짐·시그니처 변경 시 1.0. 경험치는 대상 아님
- 처치·드랍 방송은 TitleForge 닉네임으로 보인다

## 2026-09-30 — 저장소에 올림

- 워크스페이스 작업(2026-09-11 ~ 25)을 처음으로 이 저장소에 올렸다. 그 전 저장소는 2026-08-29 의 1.0.0 그대로였다
- **이 폴더 혼자서는 빌드되지 않는다** — 자체 `gradlew`·`settings.gradle.kts`·`gradle/libs.versions.toml` 을 지웠다.
  워크스페이스 루트에서 `./gradlew :monster:build`(옆에 `inmc-core` 가 있어야 한다)
- core 로 옮겨 여기서 지운 것 16개: `ConfigService` · `ConfirmMenu` · `Editors` · `Icon` · `ChatPrompt` · `CustomItemHook` ·
  `EconomyHook` · `MMOItemsHook` · `PluginClasses` · `ItemMatcher` · `ItemRef` · `ItemResolver` · `StoredItem` · `MenuListener` ·
  `Numbers` · `Text`
- `bin/`(VS Code 자바 확장이 만드는 소스 복사본)을 `.gitignore` 에 넣었다

## 2026-09-25 — 커스텀아이템 연동

- 커스텀아이템에서 **"몬스터 드랍"**(몹·확률·개수)·**"몬스터 장비"**(몹·칸·떨굴 확률) 역할을 붙이면 그 몹의 드랍 표·장비에 더해진다.
  몹 정의의 바닐라가 아닌 드랍·장비 아이템은 처음 한 번 커스텀아이템으로 옮기고 참조로 바꿔 적는다

## 2026-09-23 — 검증

- 테스트 **153개 통과**. 권한 선언(`monster.admin`) · 명령어 가드 · 메시지 키 · 설정 키를 점검했고 결함은 없었습니다
- 업적 플러그인이 `CustomMobDeathEvent` 를 **구독만** 합니다 — 이 플러그인 코드는 바뀌지 않았습니다
- `GUIDE.md` 추가

## 2026-09-11 — core 승격 작업

- **이중 기록 제거**: 등장 조건 진행도를 `trigger-progress.yml` 과 공유 저장소에 둘 다 쓰던 것을 공유 저장소
  (`monster-trigger` 구역) 하나로. 옛 파일은 부팅 때 한 번 옮기고 `.imported` 로 이름만 바꿉니다(한 릴리스 더 유지)
- 죽은 코드 `TriggerCounters.loadFrom(YamlConfiguration)` 제거
- 페이지 계산 12개 클래스 → core `Paging`, 페이지 버튼 자리 통일
- `Editors` · `ConfirmMenu` 를 core 로 옮김 (이 플러그인에서 256번 쓰이던 입력기)
- 저장 클래스들을 core `YamlFileStore`/`YamlFolder` 위로
- `Ticker` · `Messages` · `Ph` 공통부를 core 로 (`SkillTicker` 포함 — 틱 카운터 순서는 그대로 보존)
- 리로드 때 열린 화면 청소를 `Menu.owner` 기준으로 (core 소유 확인창도 닫히게)
- 레거시 스케줄러 호출 일부가 남아 있습니다(Folia 호환 아님)
