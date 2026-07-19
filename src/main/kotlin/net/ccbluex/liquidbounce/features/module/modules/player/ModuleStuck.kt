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
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket

/**
 * Stuck module
 *
 * Freezes the player and keeps the server-side position stationary.
 */
object ModuleStuck : ClientModule("Stuck", ModuleCategories.PLAYER, disableOnQuit = true) {

    private var hoverY = Double.NaN
    private var ticksSincePosition = 0
    private var jitterFlip = false

    override fun onEnabled() {
        hoverY = player.y
        ticksSincePosition = 0
        jitterFlip = false
        super.onEnabled()
    }

    override fun onDisabled() {
        ticksSincePosition = 0
        hoverY = Double.NaN
        jitterFlip = false
        super.onDisabled()
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (player.hurtTime > 0) {
            enabled = false
        }
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> { event ->
        event.cancelEvent()
    }

    @Suppress("unused")
    private val networkMovementHandler = handler<PlayerNetworkMovementTickEvent> { event ->
        event.resetPositionReminder = true
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.INCOMING && event.packet is ClientboundPlayerPositionPacket) {
            enabled = false
        }
    }

    internal fun createMovementPacket(): ServerboundMovePlayerPacket {
        val jitter = if (jitterFlip) 0.005f else -0.005f
        jitterFlip = !jitterFlip
        ticksSincePosition++

        if (ticksSincePosition >= POSITION_INTERVAL) {
            ticksSincePosition = 0
            return ServerboundMovePlayerPacket.PosRot(
                player.x,
                hoverY.takeUnless { it.isNaN() } ?: player.y,
                player.z,
                player.yRot + jitter,
                player.xRot + jitter * 0.5f,
                false,
                false
            )
        }

        return ServerboundMovePlayerPacket.Rot(
            player.yRot + jitter,
            player.xRot + jitter * 0.5f,
            false,
            false
        )
    }

    private const val POSITION_INTERVAL = 10

}
