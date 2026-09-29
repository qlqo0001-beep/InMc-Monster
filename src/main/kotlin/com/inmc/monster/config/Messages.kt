package com.inmc.monster.config

import com.inmc.monster.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * Message catalogue backed by `messages.yml`.
 *
 * Every key has a built-in Korean fallback so an admin deleting a line - or a config file that
 * predates a new key - degrades to a sensible default instead of an empty message.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = mapOf(
            PREFIX to "<gradient:#c62828:#ff8a65>[ 몬스터 ]</gradient> ",

            "no-permission" to "<red>권한이 없습니다.</red>",
            "player-only" to "<red>이 명령어는 플레이어만 사용할 수 있습니다.</red>",
            "not-ready" to "<gray>플러그인이 아직 준비 중입니다. 잠시 후 다시 시도해주세요.</gray>",
            "usage" to "<gray>/몹 <white>[gui|소환|목록|생성|삭제|복제|정리|상태|리로드]</white></gray>",

            "reloading" to "<gray>설정을 다시 읽는 중...</gray>",
            "reloaded" to "<green>설정을 다시 읽었습니다. <gray>(몬스터 {개수}종)</gray></green>",
            "reload-menu-closed" to "<gray>설정을 다시 읽어 열려 있던 창을 닫았습니다. 다시 열어주세요.</gray>",

            "mob-unknown" to "<red>'{몬스터}' 이라는 몬스터를 찾을 수 없습니다.</red>",
            "mob-created" to "<green>'{몬스터}' 몬스터를 만들었습니다.</green>",
            "mob-exists" to "<red>'{몬스터}' 몬스터가 이미 존재합니다.</red>",
            "mob-invalid-name" to "<red>몬스터 이름은 한글/영문/숫자/_/- 만 사용할 수 있습니다. (32자 이내)</red>",
            "mob-deleted" to "<yellow>'{몬스터}' 몬스터를 삭제했습니다.</yellow>",
            "mob-copied" to "<green>'{몬스터}' 을(를) 복제했습니다.</green>",
            "mob-copy-failed" to "<red>몬스터를 복제하지 못했습니다. 콘솔 로그를 확인해주세요.</red>",
            "mob-disabled" to "<red>'{몬스터}' 은(는) 비활성화 상태입니다.</red>",

            "spawn-success" to "<green>'{몬스터}' 을(를) {좌표} 에 소환했습니다. <gray>({개수}마리)</gray></green>",
            "spawn-failed" to "<red>몬스터를 소환하지 못했습니다. 엔티티 타입이나 위치를 확인해주세요.</red>",
            "spawn-budget-full" to "<red>스폰 한도에 도달했습니다. <gray>(/몹 상태 로 확인)</gray></red>",
            "spawn-world-disabled" to "<red>이 월드에서는 커스텀 몬스터가 비활성화되어 있습니다.</red>",

            "cleanup-done" to "<yellow>커스텀 몬스터 {개수}마리를 제거했습니다.</yellow>",
            "cleanup-usage" to "<gray>사용법: <white>/몹 정리 [전체|월드|<몹이름>]</white></gray>",

            "boss-bar" to "<red>{몬스터}</red> <gray>|</gray> <white>{페이즈}</white>",
            "nameplate" to "<white>{몬스터}</white> <gray>[<red>{체력}</red>/<red>{최대체력}</red>]</gray>",
            "nameplate-level" to "<dark_gray>Lv.</dark_gray><yellow>{레벨}</yellow> ",

            "mob-spawn-announce" to "<gold>★</gold> <red>{몬스터}</red><gray>이(가) </gray><white>{좌표}</white><gray>에 나타났습니다!</gray>",
            "mob-death-announce" to "<gold>★</gold> <yellow>{플레이어네임}</yellow><gray>님이 </gray><red>{몬스터}</red><gray>을(를) 처치했습니다!</gray>",
            "drop-announce" to "<gold>★</gold> <yellow>{플레이어네임}</yellow><gray>님이 </gray><red>{몬스터}</red><gray>에게서 </gray><aqua>{아이템}</aqua><gray>을(를) 획득했습니다!</gray>",
            "phase-change" to "<red>{몬스터}</red><gray>이(가) </gray><yellow>{페이즈}</yellow><gray> 단계에 들어섰습니다!</gray>",

            "drop-inventory-full" to "<yellow>인벤토리가 가득 차서 일부 아이템을 바닥에 떨어뜨렸습니다.</yellow>",
            "drop-protected" to "<gray>이 아이템은 아직 다른 플레이어가 주울 수 없습니다.</gray>",

            "respawn-warning" to "<yellow>{몬스터}</yellow><gray>이(가) </gray><white>{시간}</white><gray> 후에 등장합니다.</gray>",

            "prompt-cancelled" to "<gray>입력을 취소했습니다.</gray>",
            "prompt-timeout" to "<gray>입력 시간이 초과되었습니다.</gray>",
            "prompt-invalid-number" to "<red>숫자를 입력해주세요.</red>",
            "prompt-enter" to "<yellow>채팅으로 값을 입력하세요. <gray>('취소' 입력 시 중단)</gray></yellow>",
        )
    }
}
