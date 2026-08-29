package com.inmc.monster.item

import com.inmc.monster.integration.CustomItemHook
import com.inmc.monster.integration.MMOItemsHook
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.logging.Logger

/**
 * Turns a live [ItemStack] into a [StoredItem] and back.
 *
 * Identification order is most-specific first: MMOItems, then other namespaced custom-item
 * plugins, then plain vanilla. Anything left over - a vanilla item carrying a custom name,
 * lore, enchantments or model data - cannot be named by a reference, so it is captured as a
 * snapshot instead.
 */
/**
 * A menu icon together with how it was produced.
 *
 * [resolved] false means the reference could not be rebuilt - the source plugin is missing or
 * the id was deleted - so menus must say so rather than showing a lookalike.
 */
data class ResolvedIcon(
    val stack: ItemStack,
    val resolved: Boolean,
    val usedSnapshot: Boolean,
) {
    /** Warning lines to append to the icon's lore, empty when everything resolved cleanly. */
    fun notes(): List<String> = when {
        !resolved -> listOf("<red>⚠ 원본 아이템을 불러올 수 없습니다</red>", "<dark_gray>플러그인이 꺼져 있거나 ID가 삭제되었습니다</dark_gray>")
        usedSnapshot -> listOf("<yellow>스냅샷(고정) 아이템입니다</yellow>")
        else -> emptyList()
    }
}

class ItemResolver(
    private val mmoItems: MMOItemsHook,
    private val customItems: CustomItemHook,
    private val logger: Logger,
) {

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ResolvedIcon>()

    /** Derives the best available reference for [stack]. */
    fun identify(stack: ItemStack): ItemRef = identifyAll(stack).firstOrNull() ?: ItemRef.None

    /**
     * Every reference that could describe [stack], most specific first.
     *
     * Ordinarily only the head matters, and MMOItems leads because an MMOItems item carries
     * more definition than the material it is built on. But an item can belong to two plugins
     * at once - registering an ItemsAdder item as an MMOItems template leaves both plugins'
     * tags on the stack - and then "most specific" is a guess, not a fact. The reward editor
     * shows this list so the admin can say which plugin actually owns the item.
     */
    fun identifyAll(stack: ItemStack): List<ItemRef> {
        val candidates = ArrayList<ItemRef>(3)
        mmoItems.identify(stack)?.let { candidates += it }
        candidates += customItems.identifyAll(stack)
        // Only offered when it really is a plain stack: picking it for a decorated item would
        // quietly downgrade the reward to a bare vanilla one.
        if (isPlainVanilla(stack)) candidates += ItemRef.Vanilla(stack.type)
        return candidates
    }

    /**
     * Re-derives the candidate list for an already-stored reward from its snapshot.
     *
     * Works because [capture] keeps a snapshot for everything that is not plain vanilla, so
     * the original stack is still on hand long after the admin dropped it into the menu.
     * Returns an empty list when there is nothing to re-read.
     */
    fun candidates(item: StoredItem): List<ItemRef> {
        val original = fromSnapshot(item, 1) ?: return emptyList()
        val found = identifyAll(original).toMutableList()
        // Keep whatever is stored selectable even if its plugin is currently off, so turning a
        // plugin off for an evening cannot silently strip the choice.
        if (item.ref != ItemRef.None && found.none { it == item.ref }) found.add(0, item.ref)
        return found
    }

    /** True when the stack is indistinguishable from a freshly created `ItemStack(type)`. */
    fun isPlainVanilla(stack: ItemStack): Boolean =
        ItemStack(stack.type).isSimilar(stack)

    /**
     * Captures a stack placed into an admin GUI.
     *
     * A snapshot is stored alongside the reference whenever the reference depends on another
     * plugin still being installed, or when no reference could be derived at all. Plain
     * vanilla items keep a one-line YAML entry with no Base64 blob.
     */
    fun capture(stack: ItemStack): StoredItem {
        val single = stack.clone().also { it.amount = 1 }
        val ref = identify(single)
        val needsSnapshot = ref !is ItemRef.Vanilla
        return StoredItem(
            ref = ref,
            material = single.type,
            mode = if (ref is ItemRef.None) StorageMode.SNAPSHOT else StorageMode.REFERENCE,
            snapshot = if (needsSnapshot) runCatching { single.serializeAsBytes() }.getOrNull() else null,
            displayName = StoredItem.plainName(single),
        )
    }

    /**
     * Materialises a stored item. Falls back to the snapshot when the reference cannot be
     * resolved (source plugin missing, id deleted); returns null when nothing works, and the
     * caller skips that reward rather than handing out a barrier block.
     */
    fun create(item: StoredItem, amount: Int = 1): ItemStack? {
        if (item.mode == StorageMode.SNAPSHOT) {
            fromSnapshot(item, amount)?.let { return it }
        }
        fromRef(item.ref, amount)?.let { return it }
        fromSnapshot(item, amount)?.let { return it }
        logger.warning("아이템을 생성할 수 없습니다: ${item.ref.serialize()} (참조 해석 실패, 스냅샷 없음)")
        return null
    }

    /**
     * Icon for GUI display, plus how it was obtained.
     *
     * Menus need to know: when a reference cannot be resolved because the source plugin is
     * gone, the fallback is a plain vanilla stack of the same material, which looks like a
     * perfectly ordinary reward unless it is labelled as broken.
     */
    fun icon(item: StoredItem): ResolvedIcon {
        iconCache[cacheKey(item)]?.let { return it }

        val result = when {
            item.mode == StorageMode.SNAPSHOT && item.snapshot != null ->
                fromSnapshot(item, 1)?.let { ResolvedIcon(it, resolved = true, usedSnapshot = true) }

            else -> fromRef(item.ref, 1)?.let { ResolvedIcon(it, resolved = true, usedSnapshot = false) }
                ?: fromSnapshot(item, 1)?.let { ResolvedIcon(it, resolved = false, usedSnapshot = true) }
        } ?: ResolvedIcon(
            stack = ItemStack(item.material.takeIf { it != Material.AIR } ?: Material.BARRIER),
            resolved = false,
            usedSnapshot = false,
        )

        iconCache[cacheKey(item)] = result
        return result
    }

    /**
     * Menu icons are cached because building one is not free - a MMOItems lookup rolls stats
     * and rebuilds lore, and a reward list redraws all 45 slots on every click. Actual drops
     * go through [create], which never touches this cache, so a REFERENCE reward still picks
     * up live edits the moment it is handed out.
     */
    fun clearIconCache() {
        iconCache.clear()
    }

    /**
     * Cache identity for one stored item.
     *
     * The snapshot has to be part of this. Every hand-made vanilla item - anything with a
     * custom name, lore or enchantment - carries [ItemRef.None], which serialises to the same
     * literal "snapshot" for all of them. Keying on the reference alone therefore made two
     * differently-named sticks share one cache entry, so whichever was drawn first supplied
     * the icon for both, in the admin list *and* in the player-facing chance table. The drops
     * were always correct; only the pictures lied, which is the hardest kind of wrong to spot.
     */
    private fun cacheKey(item: StoredItem): String = buildString {
        append(item.ref.serialize()).append('|')
        append(item.mode.name).append('|')
        append(item.material.name).append('|')
        append(item.displayName ?: "").append('|')
        append(item.snapshot?.contentHashCode() ?: 0)
    }

    private fun fromRef(ref: ItemRef, amount: Int): ItemStack? {
        val stack = when (ref) {
            is ItemRef.Vanilla -> if (ref.material == Material.AIR) null else ItemStack(ref.material)
            is ItemRef.MMOItems -> mmoItems.create(ref)
            is ItemRef.Namespaced -> customItems.create(ref)
            is ItemRef.None -> null
        } ?: return null
        if (stack.type == Material.AIR) return null
        stack.amount = amount.coerceIn(1, stack.maxStackSize.coerceAtLeast(1))
        return stack
    }

    private fun fromSnapshot(item: StoredItem, amount: Int): ItemStack? {
        val bytes = item.snapshot ?: return null
        return runCatching {
            ItemStack.deserializeBytes(bytes).also {
                it.amount = amount.coerceIn(1, it.maxStackSize.coerceAtLeast(1))
            }
        }.getOrNull()
    }
}
