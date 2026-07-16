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
package net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.mode

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.GrimVelocityMode
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket

/**
 * JumpRest mode: Automatically jumps when taking knockback to reset velocity.
 * Uses hurtTime detection and jump timing.
 */
object GrimVelocityJumpRest : GrimVelocityMode("JumpRest") {

    private val jumpReset by boolean("JumpReset", true)

    private var jumpTick = 0
    private var velocityInput = false

    override fun enable() {
        jumpTick = 0
        velocityInput = false
    }

    override fun disable() {
        jumpTick = 0
        velocityInput = false
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (packet is ClientboundSetEntityMotionPacket && packet.id == player.id) {
            if (packet.movement.y < 0 || player.abilities.mayfly) return@handler
            velocityInput = true
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!jumpReset) return@handler

        if (player == null || mc.gui.screen() != null) return@handler

        if (velocityInput && player.hurtTime > 0) {
            jumpTick++
            if (player.onGround() && jumpTick <= 1) {
                mc.options.keyJump.setDown(true)
            } else if (jumpTick > 1) {
                mc.options.keyJump.setDown(false)
                velocityInput = false
            }
        } else {
            jumpTick = 0
        }
    }
}
