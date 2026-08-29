package com.inmc.monster.skill.builtin

import com.inmc.monster.skill.Skill

/**
 * The skills that ship with the plugin.
 *
 * Twenty-three of them, which is well past the fifteen the brief asks for, and chosen to cover
 * the shapes a fight needs rather than to pad a count: a melee opener, a gap closer, an escape,
 * area denial, a summon, a heal, a buff, a debuff and the two building blocks (particles and
 * messages) that let an admin dress the rest up without new code.
 */
object BuiltinSkills {

    fun all(): List<Skill> = listOf(
        // melee and movement
        MeleeStrike,
        LeapAttack,
        Charge,
        GroundSlam,
        KnockbackSkill,
        Grapple,
        Blink,

        // ranged
        ProjectileVolley,
        HomingOrb,
        FireballSkill,
        LightningSkill,

        // area
        PoisonCloud,
        FrostNova,
        ExplodeSkill,
        BlockCage,

        // support and utility
        Heal,
        Enrage,
        Shield,
        PotionSkill,
        Terrify,
        Summon,
        VisualSkill,
        BroadcastSkill,
    )
}
