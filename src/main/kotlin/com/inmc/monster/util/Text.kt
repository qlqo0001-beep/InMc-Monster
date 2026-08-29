package com.inmc.monster.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player

/**
 * Single entry point for turning a configured string into a [Component].
 *
 * Pipeline: our own `{...}` tokens -> PlaceholderAPI (when installed) -> legacy colour
 * codes translated to MiniMessage tags -> MiniMessage deserialize. Translating legacy
 * codes before deserializing lets an admin mix `&a` and `<green>` in the same string
 * without either breaking.
 */
object Text {

    private val MM: MiniMessage = MiniMessage.miniMessage()
    private val PLAIN = PlainTextComponentSerializer.plainText()

    private const val SECTION = '§'
    private const val AMP = '&'

    /** Installed by PapiHook when PlaceholderAPI is present; null otherwise. */
    @Volatile
    var papiResolver: ((Player?, String) -> String)? = null

    private val LEGACY_TAGS: Map<Char, String> = mapOf(
        '0' to "black",
        '1' to "dark_blue",
        '2' to "dark_green",
        '3' to "dark_aqua",
        '4' to "dark_red",
        '5' to "dark_purple",
        '6' to "gold",
        '7' to "gray",
        '8' to "dark_gray",
        '9' to "blue",
        'a' to "green",
        'b' to "aqua",
        'c' to "red",
        'd' to "light_purple",
        'e' to "yellow",
        'f' to "white",
        'k' to "obfuscated",
        'l' to "bold",
        'm' to "strikethrough",
        'n' to "underlined",
        'o' to "italic",
        // No 'r' entry: MiniMessage has no <reset> tag, so emitting one would leave the
        // literal text "<reset>" in the message. &r is handled separately below.
    )

    fun render(raw: String?, ph: Ph? = null, viewer: Player? = null): Component {
        val text = prepare(raw, ph, viewer)
        if (text.isEmpty()) return Component.empty()
        return MM.deserialize(text)
    }

    /** Item display names and lore must not inherit the vanilla purple italic. */
    fun renderFlat(raw: String?, ph: Ph? = null, viewer: Player? = null): Component =
        render(raw, ph, viewer).decoration(TextDecoration.ITALIC, false)

    fun renderLore(lines: List<String>, ph: Ph? = null, viewer: Player? = null): List<Component> =
        lines.map { renderFlat(it, ph, viewer) }

    /** Strips all formatting - used for console logs and Discord webhook payloads. */
    fun plain(raw: String?, ph: Ph? = null, viewer: Player? = null): String =
        PLAIN.serialize(render(raw, ph, viewer))

    fun plain(component: Component): String = PLAIN.serialize(component)

    private fun prepare(raw: String?, ph: Ph?, viewer: Player?): String {
        if (raw.isNullOrEmpty()) return ""
        var text: String = raw
        if (ph != null) text = ph.apply(text)
        if (text.indexOf('%') >= 0) {
            val resolver = papiResolver
            if (resolver != null) text = resolver(viewer, text)
        }
        return legacyToMiniMessage(text)
    }

    /** Converts `&a` / section-sign runs into MiniMessage tags, including `&#rrggbb` hex. */
    fun legacyToMiniMessage(input: String): String {
        if (input.indexOf(AMP) < 0 && input.indexOf(SECTION) < 0) return input
        val out = StringBuilder(input.length + 16)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if ((c == AMP || c == SECTION) && i + 1 < input.length) {
                val next = input[i + 1]
                if (next == '#' && i + 8 <= input.length) {
                    val hex = input.substring(i + 2, i + 8)
                    if (hex.length == 6 && hex.all { isHexDigit(it) }) {
                        out.append("<#").append(hex).append('>')
                        i += 8
                        continue
                    }
                }
                if (next.lowercaseChar() == 'r') {
                    // &r drops all styling. MiniMessage expresses that as <reset> only inside
                    // a tag-closing context, so emit explicit "off" tags instead.
                    out.append("<white><!bold><!italic><!underlined><!strikethrough><!obfuscated>")
                    i += 2
                    continue
                }
                val tag = LEGACY_TAGS[next.lowercaseChar()]
                if (tag != null) {
                    out.append('<').append(tag).append('>')
                    i += 2
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c.lowercaseChar() in 'a'..'f'
}
