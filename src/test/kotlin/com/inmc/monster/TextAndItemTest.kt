package com.inmc.monster

import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StorageMode
import kr.inmc.core.item.StoredItem
import com.inmc.monster.mob.MobDefinition
import kr.inmc.core.util.Numbers
import com.inmc.monster.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextTest {

    @Test
    fun `legacy colour codes render as colour, not as literal text`() {
        // Reported against the random-box plugin: "&6 같은 색상 코드가 작동해야 함". An admin who
        // types &c must not see the two characters in chat.
        val rendered = Text.plain(Text.render("&c빨간색"))
        assertEquals("빨간색", rendered)
    }

    @Test
    fun `minimessage tags render`() {
        assertEquals("빨간색", Text.plain(Text.render("<red>빨간색</red>")))
    }

    @Test
    fun `both styles mix in one string`() {
        assertEquals("빨강파랑", Text.plain(Text.render("&c빨강<blue>파랑</blue>")))
    }

    @Test
    fun `hex colour codes render`() {
        assertEquals("헥스", Text.plain(Text.render("&#ff8800헥스")))
    }

    @Test
    fun `a reset code does not leak literal tag text`() {
        // MiniMessage has no <reset>, so a naive translation would print the tag itself.
        val rendered = Text.plain(Text.render("&c빨강&r보통"))
        assertEquals("빨강보통", rendered)
        assertFalse(rendered.contains("<"), "reset leaked a literal tag: $rendered")
    }

    @Test
    fun `plain text passes through untouched`() {
        assertEquals("그냥 텍스트", Text.plain(Text.render("그냥 텍스트")))
    }

    @Test
    fun `null and empty input are safe`() {
        assertEquals("", Text.plain(Text.render(null)))
        assertEquals("", Text.plain(Text.render("")))
    }

    @Test
    fun `an unclosed tag does not throw`() {
        // Hand-typed lore will contain broken markup. Rendering must degrade, not crash the GUI.
        val rendered = runCatching { Text.plain(Text.render("<red>열린 채로")) }.getOrNull()
        assertNotNull(rendered, "an unclosed tag threw instead of degrading")
    }

    @Test
    fun `legacy conversion leaves strings without codes alone`() {
        val input = "코드 없는 문자열"
        assertEquals(input, Text.legacyToMiniMessage(input))
    }
}

class PlaceholderTest {

    @Test
    fun `korean and english aliases resolve to the same value`() {
        val ph = Ph.of().mob("부패한 기사").player("스티브")
        assertEquals("부패한 기사", ph.apply("{몬스터}"))
        assertEquals("부패한 기사", ph.apply("{몹}"))
        assertEquals("부패한 기사", ph.apply("{mob}"))
        assertEquals("스티브", ph.apply("{플레이어네임}"))
        assertEquals("스티브", ph.apply("{player}"))
    }

    @Test
    fun `several tokens in one string all resolve`() {
        val ph = Ph.of().player("스티브").mob("좀비").level(12)
        assertEquals(
            "스티브 님이 Lv.12 좀비 처치",
            ph.apply("{플레이어네임} 님이 Lv.{레벨} {몬스터} 처치"),
        )
    }

    @Test
    fun `unset tokens are left as-is rather than blanked`() {
        // Blanking would hide a typo; leaving the token visible makes it obvious in-game.
        val ph = Ph.of().mob("좀비")
        assertEquals("{플레이어네임} 좀비", ph.apply("{플레이어네임} {몬스터}"))
    }

    @Test
    fun `location fills the combined token and its parts`() {
        val ph = Ph.of().location("world", 10, 64, -20)
        assertEquals("world 10, 64, -20", ph.apply("{좌표}"))
        assertEquals("world", ph.apply("{월드}"))
        assertEquals("10", ph.apply("{x}"))
        assertEquals("-20", ph.apply("{z}"))
    }

    @Test
    fun `an unknown y is rendered as a question mark rather than zero`() {
        val ph = Ph.of().location("world", 10, null, -20)
        assertEquals("world 10, ?, -20", ph.apply("{좌표}"))
    }

    @Test
    fun `copies do not share state`() {
        val base = Ph.of().mob("좀비")
        val copy = base.copy().player("스티브")
        assertEquals("{플레이어네임}", base.apply("{플레이어네임}"))
        assertEquals("스티브", copy.apply("{플레이어네임}"))
    }

    @Test
    fun `an empty bag changes nothing`() {
        assertEquals("{몬스터}", Ph.of().apply("{몬스터}"))
    }
}

class ItemRefTest {

    @Test
    fun `references round trip through their serialised form`() {
        val cases = listOf(
            ItemRef.Vanilla(Material.DIAMOND),
            ItemRef.MMOItems("SWORD", "EXCALIBUR"),
            ItemRef.Namespaced("itemsadder", "ruby"),
            ItemRef.None,
        )
        for (ref in cases) {
            assertEquals(ref, ItemRef.parse(ref.serialize()), "round trip failed for ${ref.serialize()}")
        }
    }

    @Test
    fun `a bare material name parses as vanilla`() {
        assertEquals(ItemRef.Vanilla(Material.STONE), ItemRef.parse("STONE"))
        assertEquals(ItemRef.Vanilla(Material.STONE), ItemRef.parse("stone"))
    }

    @Test
    fun `mmoitems ids are normalised to upper case`() {
        val ref = ItemRef.parse("mmoitems:sword:excalibur")
        assertEquals(ItemRef.MMOItems("SWORD", "EXCALIBUR"), ref)
    }

    @Test
    fun `an unknown namespace is kept rather than discarded`() {
        // Discarding it would turn a Nexo item into a snapshot the moment the plugin was off.
        val ref = ItemRef.parse("nexo:magic_sword")
        assertEquals(ItemRef.Namespaced("nexo", "magic_sword"), ref)
    }

    @Test
    fun `unparseable input falls back to snapshot`() {
        assertEquals(ItemRef.None, ItemRef.parse(null))
        assertEquals(ItemRef.None, ItemRef.parse(""))
        assertEquals(ItemRef.None, ItemRef.parse("snapshot"))
        assertEquals(ItemRef.None, ItemRef.parse("minecraft:not_a_real_item"))
    }

    @Test
    fun `stored items round trip through yaml`() {
        val item = StoredItem(
            ref = ItemRef.MMOItems("SWORD", "EXCALIBUR"),
            material = Material.IRON_SWORD,
            mode = StorageMode.SNAPSHOT,
            snapshot = byteArrayOf(1, 2, 3),
            displayName = "엑스칼리버",
        )
        val yaml = YamlConfiguration()
        item.save(yaml)
        val loaded = StoredItem.load(YamlConfiguration().apply { loadFromString(yaml.saveToString()) })

        assertNotNull(loaded)
        assertEquals(item.ref, loaded.ref)
        assertEquals(item.material, loaded.material)
        assertEquals(StorageMode.SNAPSHOT, loaded.mode)
        assertEquals("엑스칼리버", loaded.displayName)
        assertTrue(item.snapshot.contentEquals(loaded.snapshot))
    }

    @Test
    fun `an entry with neither reference nor snapshot is rejected`() {
        // Loading it would produce a drop that can never be built, and the failure would only
        // show up as a missing item at kill time.
        val yaml = YamlConfiguration()
        yaml.set("item", "snapshot")
        assertNull(StoredItem.load(yaml))
    }

    @Test
    fun `the label prefers the display name and falls back to the reference`() {
        assertEquals(
            "엑스칼리버",
            StoredItem(ItemRef.MMOItems("SWORD", "EXCALIBUR"), Material.IRON_SWORD, displayName = "엑스칼리버")
                .label(),
        )
        assertEquals(
            "mmoitems:SWORD:EXCALIBUR",
            StoredItem(ItemRef.MMOItems("SWORD", "EXCALIBUR"), Material.IRON_SWORD).label(),
        )
    }

    @Test
    fun `storage mode parsing tolerates junk`() {
        assertEquals(StorageMode.REFERENCE, StorageMode.parse(null))
        assertEquals(StorageMode.REFERENCE, StorageMode.parse("nonsense"))
        assertEquals(StorageMode.SNAPSHOT, StorageMode.parse("snapshot"))
        assertEquals(StorageMode.SNAPSHOT, StorageMode.REFERENCE.toggle())
    }
}

class InheritanceTest {

    private fun parent(): MobDefinition = MobDefinition("부모").apply {
        displayName = "<gray>기본 언데드</gray>"
        entityType = EntityType.SKELETON
        model = "undead_base"
        stats["MAX_HEALTH"] = 100.0
        stats["ARMOR"] = 5.0
        customStats["CRITICAL_STRIKE_CHANCE"] = 10.0
        tags = linkedSetOf("undead")
        skills.add(com.inmc.monster.skill.SkillInstance(skillId = "melee_strike"))
        drops.entries.add(
            com.inmc.monster.mob.MobDrop(
                id = "bone",
                item = StoredItem(ItemRef.Vanilla(Material.BONE), Material.BONE),
                chance = 50.0,
            ),
        )
    }

    @Test
    fun `a child keeps its own values and inherits the rest`() {
        val child = MobDefinition("자식").apply {
            stats["MAX_HEALTH"] = 250.0
        }
        child.inheritFrom(parent())

        assertEquals(250.0, child.stats["MAX_HEALTH"], "the child's own value must win")
        assertEquals(5.0, child.stats["ARMOR"], "unset stats must be inherited")
        assertEquals(10.0, child.customStats["CRITICAL_STRIKE_CHANCE"])
    }

    @Test
    fun `appearance is inherited only when the child never set it`() {
        val child = MobDefinition("자식")
        child.inheritFrom(parent())
        assertEquals("<gray>기본 언데드</gray>", child.displayName)
        assertEquals("undead_base", child.model)
        assertEquals(EntityType.SKELETON, child.entityType)

        val named = MobDefinition("이름있음").apply {
            displayName = "<red>내 이름</red>"
            model = "my_model"
        }
        named.inheritFrom(parent())
        assertEquals("<red>내 이름</red>", named.displayName)
        assertEquals("my_model", named.model)
    }

    @Test
    fun `collections are inherited only when empty`() {
        val child = MobDefinition("자식")
        child.inheritFrom(parent())
        assertEquals(1, child.skills.size)
        assertEquals(1, child.drops.entries.size)
        assertEquals(setOf("undead"), child.tags)

        val own = MobDefinition("고유").apply {
            skills.add(com.inmc.monster.skill.SkillInstance(skillId = "explode"))
        }
        own.inheritFrom(parent())
        assertEquals(1, own.skills.size, "a child with its own skills must not gain the parent's")
        assertEquals("explode", own.skills.first().skillId)
    }

    @Test
    fun `inherited skills are copies, not shared references`() {
        // Sharing would make an edit to one mob silently change every sibling that inherits it.
        val source = parent()
        val child = MobDefinition("자식")
        child.inheritFrom(source)

        child.skills.first().cooldownTicks = 999
        assertEquals(100, source.skills.first().cooldownTicks)
    }

    @Test
    fun `inherited drops are copies`() {
        val source = parent()
        val child = MobDefinition("자식")
        child.inheritFrom(source)

        child.drops.entries.first().chance = 1.0
        assertEquals(50.0, source.drops.entries.first().chance)
    }
}

class NumbersTest {

    @Test
    fun `chances are clamped to the usable range`() {
        assertEquals(0.01, Numbers.clampChance(-5.0))
        assertEquals(100.0, Numbers.clampChance(500.0))
        assertEquals(12.35, Numbers.clampChance(12.345))
    }

    @Test
    fun `chance formatting drops pointless decimals`() {
        assertEquals("50", Numbers.chance(50.0))
        assertEquals("12.34", Numbers.chance(12.34))
        assertEquals("12.3", Numbers.chance(12.30))
    }

    @Test
    fun `rounding is to two places`() {
        assertEquals(1.23, Numbers.round2(1.234))
        assertEquals(1.24, Numbers.round2(1.235))
    }
}
