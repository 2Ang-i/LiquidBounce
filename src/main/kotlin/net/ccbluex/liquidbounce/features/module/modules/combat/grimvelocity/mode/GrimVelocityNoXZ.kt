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
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.GrimVelocityMode
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.ModuleGrimVelocity
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.handlePacket
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.concurrent.LinkedBlockingQueue

/**
 * NoXZ mode: Cancels XZ velocity by delaying packets, tracking target position,
 * and performing sprint-based attacks when knockback is detected.
 */
object GrimVelocityNoXZ : GrimVelocityMode("NoXZ") {

    val strength by float("Strength", 0.15f, 0.05f..1.0f)
    private val debug by boolean("Debug", false)

    // State variables
    private var noXZGotKB = false
    private var noXZDelaying = false
    private var noXZSprintInStage = false
    private var noXZJump = false
    private var noXZForceSprint = false
    private var noXZDelayTicks = 0
    private var noXZLagDisable = 0
    private var noXZAttack = 0
    private var noXZTarget: Entity? = null
    private var noXZServerPos: Vec3? = null

    private val noXZDelayedPackets = LinkedBlockingQueue<Packet<*>>()

    override fun enable() {
        noXZGotKB = false
        noXZDelaying = false
        noXZSprintInStage = false
        noXZJump = false
        noXZForceSprint = false
        noXZDelayTicks = 0
        noXZLagDisable = 0
        noXZTarget = null
        noXZServerPos = null
        noXZDelayedPackets.clear()
    }

    override fun disable() {
        releaseAll()
        noXZReset()
    }

    private fun noXZReady(): Boolean = player?.isSprinting == true

    private fun inRaycast(entity: Entity): Boolean {
        val rotation = RotationManager.currentRotation ?: return false
        val eyes = player.eyePosition
        val box = entity.boundingBox
        val closest = clipBoxClosestPoint(box, eyes)
        val toClosest = closest.subtract(eyes).normalize()
        return rotation.directionVector.dot(toClosest) > 0.99 && eyes.distanceToSqr(closest) <= 9.0
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || player == null) return@handler

        val packet = event.packet

        // Handle velocity packet
        if (packet is ClientboundSetEntityMotionPacket) {
            if (packet.id == player.id && noXZLagDisable <= 0) {
                if (packet.movement.y < 0 || player.abilities.mayfly) return@handler

                val killAura = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target else null
                if (killAura != null) {
                    val hx = packet.movement.x
                    val hz = packet.movement.z
                    val kbStrength = kotlin.math.sqrt(hx * hx + hz * hz)
                    if (kbStrength >= strength) {
                        noXZGotKB = true
                        if (!noXZDelaying) {
                            noXZDelaying = true
                            noXZTarget = killAura
                            noXZServerPos = killAura.position()
                        }
                        if (!noXZReady()) {
                            noXZSprintInStage = true
                        }
                    }
                }
            }
        }

        // Track target movement
        if (packet is ClientboundMoveEntityPacket) {
            val entity = packet.getEntity(world)
            val killAura = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target else null
            if (entity != null && entity == killAura) {
                noXZServerPos?.let { serverPos ->
                    val dx = if (packet.hasPosition()) packet.xa / 4096.0 else 0.0
                    val dy = if (packet.hasPosition()) packet.ya / 4096.0 else 0.0
                    val dz = if (packet.hasPosition()) packet.za / 4096.0 else 0.0
                    noXZServerPos = serverPos.add(dx, dy, dz)
                }
            }
        }

        // Track target teleport
        if (packet is ClientboundTeleportEntityPacket) {
            val killAura = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target else null
            if (killAura != null && packet.id == killAura.id) {
                noXZServerPos = packet.change.position
            }
        }

        // Flag detection
        if (packet is ClientboundPlayerPositionPacket && noXZGotKB) {
            if (debug) chat("NoXZ Flag Detected")
            noXZLagDisable = 2
            noXZReset()
        }

        // Delay packets
        if (noXZDelaying && canDelayPacket(packet) && !event.isCancelled) {
            noXZDelayedPackets.add(packet)
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (player == null) return@handler

        if (noXZLagDisable > 0) {
            noXZLagDisable--
            noXZReset()
            return@handler
        }

        if (noXZDelaying) {
            if (noXZDelayTicks >= 20) {
                if (debug) chat("NoXZ Timed Out")
                noXZReset()
            }
            noXZDelayTicks++

            val killAura = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target else null
            if (killAura == null) {
                noXZReset()
                return@handler
            }

            if (!noXZSprintInStage && noXZReady() && inRaycast(killAura)) {
                sync(false)
                noXZAttack = 1
                noXZTarget = killAura
            } else if (!noXZReady()) {
                noXZSprintInStage = true
            }
        } else {
            noXZServerPos = null
        }

        if (noXZTarget == null && noXZAttack > 0) {
            if (debug) chat("NoXZ Target is null")
            noXZReset()
        }
    }

    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        if (player == null) return@handler

        if (mc.gui.screen() == null && (noXZDelaying || noXZAttack > 0)) {
            event.directionalInput = DirectionalInput.FORWARDS
        }

        if (noXZJump) {
            noXZJump = false
        }
    }

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent> { event ->
        if (noXZReady()) {
            noXZSprintInStage = false
        }
        if (noXZForceSprint) {
            noXZForceSprint = false
            event.sprint = true
        }
    }

    // Called from attack handler in main module or via tick
    fun handleNoXZInput() {
        if (player == null || noXZLagDisable > 0) return

        if (noXZAttack > 0) {
            noXZAttack--
            val killAura = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target else null

            if (!noXZReady()) {
                if (killAura != null && (inRaycast(killAura) || noXZIsTargetNear())) {
                    noXZDelaying = true
                    noXZGotKB = true
                    noXZSprintInStage = true
                    noXZAttack = 1
                    noXZTarget = killAura
                    if (debug) chat("NoXZ Schedule")
                } else {
                    noXZTarget = null
                    if (debug) chat("NoXZ RayTrace Failed")
                }
                return
            }

            val motion = player.deltaMovement
            val motionStrength = kotlin.math.sqrt(motion.x * motion.x + motion.z * motion.z)
            val times = ModuleGrimVelocity.getAttacks(motionStrength, strength.toDouble()) + 1
            if (debug) chat("NoXZ Attack Count: $times")

            noXZTarget?.let { target ->
                noXZRunReduce(target, times)
            }
            noXZJump = true
        }
    }

    private fun noXZRunReduce(target: Entity, times: Int) {
        if (times <= 0 || player == null) return
        if (target !is LivingEntity) return

        for (i in 0 until times) {
            sendPacketSilently(ServerboundAttackPacket(target.id))
            player.swing(InteractionHand.MAIN_HAND)
            player.deltaMovement = player.deltaMovement.multiply(0.6, 1.0, 0.6)
        }
        noXZTarget = null
        noXZGotKB = false
    }

    private fun noXZIsTargetNear(): Boolean {
        if (player == null || noXZServerPos == null || noXZTarget == null) return false
        val eyePos = player.eyePosition
        val distSqToTarget = eyePos.distanceToSqr(noXZTarget!!.position())
        val distSqToServer = eyePos.distanceToSqr(noXZServerPos!!)
        return distSqToTarget > 9.0 && distSqToServer <= 9.0
    }

    private fun sync(threadSecure: Boolean) {
        noXZDelayTicks = 0
        if (threadSecure) {
            mc.execute {
                noXZDelaying = false
                releaseAll()
            }
        } else {
            noXZDelaying = false
            releaseAll()
        }
        noXZDelayedPackets.clear()
    }

    private fun releaseAll() {
        while (!noXZDelayedPackets.isEmpty()) {
            val packet = noXZDelayedPackets.poll() ?: continue
            handlePacket(packet)
        }
    }

    private fun noXZReset() {
        noXZDelaying = false
        noXZGotKB = false
        noXZSprintInStage = false
        noXZJump = false
        noXZForceSprint = false
        noXZDelayTicks = 0
        noXZAttack = 0
        noXZTarget = null
        noXZServerPos = null
        sync(false)
    }

    private fun canDelayPacket(packet: Packet<*>): Boolean {
        return packet !is ClientboundPlayerPositionPacket
    }

    private fun clipBoxClosestPoint(box: net.minecraft.world.phys.AABB, point: Vec3): Vec3 {
        val x = point.x.coerceIn(box.minX, box.maxX)
        val y = point.y.coerceIn(box.minY, box.maxY)
        val z = point.z.coerceIn(box.minZ, box.maxZ)
        return Vec3(x, y, z)
    }
}
