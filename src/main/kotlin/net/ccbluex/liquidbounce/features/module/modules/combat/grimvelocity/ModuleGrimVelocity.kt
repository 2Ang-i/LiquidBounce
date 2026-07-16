/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.mode.GrimVelocityGrimAC
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.mode.GrimVelocityJumpRest
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.mode.GrimVelocityNoXZ
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.mode.GrimVelocityReduce
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GrimVelocity module - combined anti-kb module with JumpRest, GrimAC (Cancel XZ), NoXZ, and Reduce modes.
 *
 * Fully ported from Mizore client's Velocity module.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ModuleGrimVelocity : ClientModule(
    "GrimVelocity",
    ModuleCategories.COMBAT,
    aliases = listOf("nokb", "antikb", "velo", "grimvelo")
) {

    val modes = choices(
        "Mode",
        GrimVelocityJumpRest,
        arrayOf(
            GrimVelocityJumpRest,
            GrimVelocityGrimAC,
            GrimVelocityNoXZ,
            GrimVelocityReduce,
        )
    ).apply(::tagBy)

    // === Common Settings ===
    private val delay by intRange("Delay", 0..0, 0..40, "ticks")
    private val pauseOnFlag by int("PauseOnFlag", 0, 0..20, "ticks")
    val cooling by boolean("Cooling", true)

    // === Global State ===
    internal var pause = 0

    /** Static attack queue - accessed externally via BackTrack-like modules */
    @JvmField
    var attackQueue = 0

    /** Inbound packet queue for GrimAC mode */
    @JvmField
    var inBoundOpen = false

    /** Tick counter for GrimAC tracking */
    @JvmField
    var grimTicks = 0

    // === Attack Controller (ported from CombatController) ===
    private val cps = ArrayList<Int>()
    private val attackLock = AtomicBoolean(false)
    private var attackTick = Int.MIN_VALUE

    private val chronometer = Chronometer()
    private var coolingNotification = false

    /**
     * Calculate attack count based on horizontal velocity strength.
     * Simulates successive 0.6x velocity reductions until below threshold.
     */
    fun getAttacks(strength: Double, threshold: Double): Int {
        if (strength < threshold) return -1
        var s = strength
        for (i in 0 until 5) {
            s *= 0.6
            if (s < threshold) {
                return i
            }
        }
        return 4
    }

    /**
     * Attack lock: only one attack allowed per tick.
     * Returns true if the attack is allowed (first call in current tick).
     */
    fun attackAndLock(): Boolean {
        if (player == null) {
            cps.clear()
            attackLock.set(false)
            attackTick = Int.MIN_VALUE
            return false
        }
        val tick = player.tickCount
        if (attackTick == tick) {
            attackLock.set(true)
            return false
        }
        attackTick = tick
        attackLock.set(true)
        return true
    }

    /** Record an attack in the current tick */
    fun recordAttack() {
        if (player == null) {
            cps.clear()
            attackLock.set(false)
            attackTick = Int.MIN_VALUE
            return
        }
        cps.add(player.tickCount)
    }

    /** Check if attack count in last 20 ticks exceeds limit */
    fun isAttackLimited(limit: Int): Boolean {
        if (player == null) {
            cps.clear()
            return false
        }
        cps.removeIf { data -> player.tickCount < data || (player.tickCount - data) > 20 }
        return cps.size >= limit
    }

    fun isAttackLocked(): Boolean {
        if (player != null && attackTick != player.tickCount) {
            return false
        }
        return attackLock.get()
    }

    fun getCpsCount(): Int = cps.size

    // === Tick Handler (reset attack lock + pause countdown) ===
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        attackLock.set(false)

        if (pause > 0) {
            pause--
        }
    }

    // === Common Packet Handler (flag detection + cooling) ===
    @Suppress("unused")
    private val commonPacketHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || !event.original || player == null) {
            return@handler
        }

        val packet = event.packet

        // S08 flag detection (ClientboundPlayerPositionPacket)
        if (packet is ClientboundPlayerPositionPacket) {
            pause = pauseOnFlag

            if (cooling) {
                coolingNotification = true
            }
            return@handler
        }

        // Cooling notification
        if (!chronometer.hasElapsed(1000L)) {
            if (cooling) return@handler
        }
        if (coolingNotification) {
            chronometer.reset()
            if (ModuleKillAura.running && ModuleKillAura.targetTracker.target != null) {
                notification(
                    "Warning",
                    "${name} Receive Lag Packet, Cooling!!!",
                    NotificationEvent.Severity.ERROR
                )
            }
            coolingNotification = false
            return@handler
        }
    }

}
