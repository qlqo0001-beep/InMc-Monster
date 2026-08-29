package com.inmc.monster.integration

import com.inmc.monster.item.ItemRef
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * Namespaced custom-item plugins (ItemsAdder, Nexo/Oraxen), reached by reflection only.
 *
 * The previous plugin compiled against the ItemsAdder API, which made the jar refuse to
 * load classes when ItemsAdder was absent. Everything here degrades to "no reference,
 * use the snapshot" instead.
 */
class CustomItemHook(private val logger: Logger) {

    private val providers = mutableListOf<Provider>()

    val isEnabled: Boolean get() = providers.isNotEmpty()

    fun setup() {
        providers.clear()
        tryItemsAdder()
        tryOraxenLike("Nexo", "com.nexomc.nexo.api.NexoItems")
        tryOraxenLike("Oraxen", "io.th0rgal.oraxen.api.OraxenItems")
        tryEcoItems()
        setupItemsAdderBlocks()
        if (providers.isEmpty()) {
            logger.info("커스텀 아이템 플러그인 없음 - 네임스페이스 참조는 스냅샷으로 대체됩니다")
        }
    }

    fun identify(stack: ItemStack): ItemRef.Namespaced? = identifyAll(stack).firstOrNull()

    /**
     * Every namespaced plugin that claims this stack, not just the first.
     *
     * One item can genuinely belong to two of them at once - an ItemsAdder item used as the
     * base of another plugin's template keeps both sets of tags - and the first match is only
     * a guess at which one the admin meant. Callers that have to pick one still take the head
     * of this list; the reward editor offers the whole list instead.
     */
    fun identifyAll(stack: ItemStack): List<ItemRef.Namespaced> {
        if (providers.isEmpty()) return emptyList()
        val found = LinkedHashSet<ItemRef.Namespaced>()
        for (provider in providers) {
            val id = provider.identify(stack) ?: continue
            val parts = id.split(':', limit = 2)
            found += if (parts.size == 2) {
                ItemRef.Namespaced(parts[0].lowercase(), parts[1])
            } else {
                ItemRef.Namespaced(provider.namespace, id)
            }
        }
        return found.toList()
    }

    fun create(ref: ItemRef.Namespaced): ItemStack? {
        for (provider in providers) {
            provider.create(ref)?.let { return it }
        }
        return null
    }

    private fun tryItemsAdder() {
        if (!PluginClasses.isPresent("ItemsAdder")) return
        try {
            val customStack = PluginClasses.require("ItemsAdder", "dev.lone.itemsadder.api.CustomStack")
            val byItemStack = customStack.getMethod("byItemStack", ItemStack::class.java)
            val getInstance = customStack.getMethod("getInstance", String::class.java)
            val getNamespacedId = customStack.getMethod("getNamespacedID")
            val getItemStack = customStack.getMethod("getItemStack")

            providers += object : Provider {
                override val namespace = "itemsadder"

                override fun identify(stack: ItemStack): String? = try {
                    byItemStack.invoke(null, stack)?.let { getNamespacedId.invoke(it) as? String }
                } catch (_: Throwable) {
                    null
                }

                override fun create(ref: ItemRef.Namespaced): ItemStack? = try {
                    getInstance.invoke(null, ref.serialize())?.let { getItemStack.invoke(it) as? ItemStack }
                } catch (_: Throwable) {
                    null
                }
            }
            logger.info("ItemsAdder 연동 활성화")
        } catch (t: Throwable) {
            logger.warning("ItemsAdder 연동 실패: ${t.message}")
        }
    }

    /** Nexo and Oraxen share the same `idFromItem` / `itemFromId` API shape. */
    private fun tryOraxenLike(pluginName: String, className: String) {
        if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) return
        try {
            val api = PluginClasses.require(pluginName, className)
            val idFrom: Method = api.methods.first {
                (it.name == "idFromItem" || it.name == "getIdByItem") &&
                    it.parameterCount == 1 && it.parameterTypes[0] == ItemStack::class.java
            }
            val builderFrom: Method = api.methods.first {
                (it.name == "itemFromId" || it.name == "getItemById") && it.parameterCount == 1
            }
            val ns = pluginName.lowercase()

            providers += object : Provider {
                override val namespace = ns

                override fun identify(stack: ItemStack): String? = try {
                    (idFrom.invoke(null, stack) as? String)?.let { "$ns:$it" }
                } catch (_: Throwable) {
                    null
                }

                override fun create(ref: ItemRef.Namespaced): ItemStack? {
                    if (ref.namespace != ns) return null
                    return try {
                        val builder = builderFrom.invoke(null, ref.id) ?: return null
                        val build = builder.javaClass.methods.firstOrNull {
                            it.name == "build" && it.parameterCount == 0
                        } ?: return builder as? ItemStack
                        build.invoke(builder) as? ItemStack
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            logger.info("$pluginName 연동 활성화")
        } catch (t: Throwable) {
            logger.warning("$pluginName 연동 실패: ${t.message}")
        }
    }

    /**
     * EcoItems (the `eco` platform).
     *
     * Identification reads the `ecoitems:item` persistent-data key with plain Bukkit API - no
     * reflection needed and no coupling to eco's internals. Creation does need the API, so it
     * is resolved reflectively and tolerates either a Kotlin `object` singleton or a static
     * accessor, since eco has shipped both shapes.
     */
    private fun tryEcoItems() {
        if (!Bukkit.getPluginManager().isPluginEnabled("EcoItems")) return
        try {
            val itemsClass = PluginClasses.require("EcoItems", "com.willfp.ecoitems.items.EcoItems")
            val singleton = runCatching { itemsClass.getField("INSTANCE").get(null) }.getOrNull()
            val getByID = itemsClass.methods.first {
                (it.name == "getByID" || it.name == "getById") &&
                    it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            }
            val idKey = NamespacedKey.fromString("ecoitems:item")

            providers += object : Provider {
                override val namespace = "ecoitems"

                override fun identify(stack: ItemStack): String? {
                    if (idKey == null) return null
                    val meta = stack.itemMeta ?: return null
                    val id = meta.persistentDataContainer
                        .get(idKey, PersistentDataType.STRING)
                        ?.takeIf { it.isNotBlank() }
                        ?: return null
                    return "$namespace:$id"
                }

                override fun create(ref: ItemRef.Namespaced): ItemStack? {
                    if (ref.namespace != namespace) return null
                    return try {
                        val item = getByID.invoke(singleton, ref.id) ?: return null
                        val accessor = item.javaClass.methods.firstOrNull {
                            (it.name == "getItemStack" || it.name == "getItem") && it.parameterCount == 0
                        } ?: return null
                        (accessor.invoke(item) as? ItemStack)?.clone()
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            logger.info("EcoItems 연동 활성화")
        } catch (t: Throwable) {
            logger.warning("EcoItems 연동 실패: ${t.message}")
        }
    }

    // --- custom blocks ---------------------------------------------------------

    /**
     * Identifies an item that places a custom block, as `namespace to id`.
     *
     * A box can be configured to look like any block, including one owned by ItemsAdder, so the
     * admin registers it by holding the block item - the same gesture as the capsule item.
     */
    fun identifyBlock(stack: ItemStack): Pair<String, String>? {
        val itemsAdder = itemsAdderBlocks ?: return null
        val id = identify(stack) ?: return null
        // Only report it as a block when ItemsAdder actually knows a block by that id.
        return if (itemsAdder.isBlock(id.serialize())) id.namespace to id.id else null
    }

    fun placeCustomBlock(block: org.bukkit.block.Block, namespace: String, id: String): Boolean {
        val itemsAdder = itemsAdderBlocks ?: return false
        return itemsAdder.place("$namespace:$id", block)
    }

    fun removeCustomBlock(block: org.bukkit.block.Block): Boolean {
        val itemsAdder = itemsAdderBlocks ?: return false
        return itemsAdder.remove(block)
    }

    private var itemsAdderBlocks: BlockProvider? = null

    private interface BlockProvider {
        fun isBlock(namespacedId: String): Boolean
        fun place(namespacedId: String, block: org.bukkit.block.Block): Boolean
        fun remove(block: org.bukkit.block.Block): Boolean
    }

    /** ItemsAdder's CustomBlock API, resolved reflectively alongside CustomStack. */
    private fun setupItemsAdderBlocks() {
        itemsAdderBlocks = null
        if (!PluginClasses.isPresent("ItemsAdder")) return
        try {
            val customBlock = PluginClasses.require("ItemsAdder", "dev.lone.itemsadder.api.CustomBlock")
            val getInstance = customBlock.getMethod("getInstance", String::class.java)
            val place = customBlock.methods.firstOrNull {
                it.name == "place" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == org.bukkit.Location::class.java
            }
            val remove = customBlock.methods.firstOrNull {
                it.name == "remove" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == org.bukkit.Location::class.java
            }

            itemsAdderBlocks = object : BlockProvider {
                override fun isBlock(namespacedId: String): Boolean = try {
                    getInstance.invoke(null, namespacedId) != null
                } catch (_: Throwable) {
                    false
                }

                override fun place(namespacedId: String, block: org.bukkit.block.Block): Boolean = try {
                    val instance = getInstance.invoke(null, namespacedId)
                    if (instance == null || place == null) false
                    else {
                        place.invoke(instance, block.location)
                        true
                    }
                } catch (t: Throwable) {
                    logger.warning("ItemsAdder 블록 배치 실패 ($namespacedId): ${t.message}")
                    false
                }

                override fun remove(block: org.bukkit.block.Block): Boolean = try {
                    remove?.invoke(null, block.location)
                    true
                } catch (_: Throwable) {
                    false
                }
            }
            logger.info("ItemsAdder 커스텀 블록 연동 활성화")
        } catch (t: Throwable) {
            logger.warning("ItemsAdder 커스텀 블록 연동 실패: ${t.message}")
        }
    }

    private interface Provider {
        val namespace: String
        fun identify(stack: ItemStack): String?
        fun create(ref: ItemRef.Namespaced): ItemStack?
    }
}
