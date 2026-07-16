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
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.GrimVelocityMode
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.ModuleGrimVelocity
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import java.util.concurrent.LinkedBlockingDeque

/**
 * GrimAC (Cancel XZ) mode: Delays packets after receiving knockback,
 * calculates required attacks to negate velocity, and performs them
 * in OneTime or PerTick batches when sprinting and aimed at target.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object GrimVelocityGrimAC : GrimVelocityMode("GrimAC") {

    val strength by float("Strength", 0.15f, 0.05f..1.0f)
    private val attackTimes by int("AttackTimes", 5, 0..20)
    private val noXZMode by enumChoice("AttackMode", AttackMode.OneTime)
    private val maxTicks by int("MaxTrackingTicks", 100, 0..500)
    private val debug by boolean("Debug", false)

    enum class AttackMode(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        OneTime("OneTime"),
        PerTick("PerTick"),
    }

    // Packet queue for delayed inbound packets
    val inBound = LinkedBlockingDeque<Packet<*>>()

    // State
    var alink = false
    var entity: Entity? = null
    var ready = false
    var jump = false
    private var noXZServerPos: Vec3? = null

    // Rotation
    private var rot = Rotation(0f, 0f)

    override fun enable() {
        ready = false
        jump = false
        entity = null
        noXZServerPos = null
        alink = false
        ModuleGrimVelocity.grimTicks = 0
        ModuleGrimVelocity.attackQueue = 0
        ModuleGrimVelocity.inBoundOpen = true
        rot = Rotation(player.yRot, player.xRot)
    }

    override fun disable() {
        ModuleGrimVelocity.inBoundOpen = false
        clear()
    }

    fun processPackets() {
        val connection = network ?: run {
            inBound.clear()
            return
        }
        while (true) {
            val packet = inBound.poll() ?: break
            try {
                @Suppress("UNCHECKED_CAST")
                (packet as Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>).handle(connection)
            } catch (e: Exception) {
                e.printStackTrace()
                inBound.clear()
                break
            }
        }
    }

    fun clear() {
        if (player == null) return
        ModuleGrimVelocity.grimTicks = 0
        alink = false
        while (!inBound.isEmpty()) {
            try {
                val packet = inBound.poll() ?: continue
                val conn = network ?: continue
                @Suppress("UNCHECKED_CAST")
                (packet as Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>).handle(conn)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Suppress("unused")
    private val rotationHandler = handler<RotationUpdateEvent> {
        if (player == null) return@handler
        if (!ModuleKillAura.running) return@handler

        if (alink) {
            val target = ModuleKillAura.targetTracker.target
            if (target != null && isAimedAt(target) && player.isSprinting) {
                entity = target
                ready = true
                clear()
            }
        }

        if (ready) {
            ready = false
            if (!player.isSprinting) {
                val killAuraTarget = ModuleKillAura.targetTracker.target
                if (killAuraTarget != null && isAimedAt(killAuraTarget)) {
                    alink = true
                    ModuleGrimVelocity.grimTicks = 0
                    entity = killAuraTarget
                    ready = true
                    if (debug) chat("Waiting Sprint")
                } else {
                    if (debug) chat("RayCast Failed")
                    entity = null
                }
                return@handler
            }

            val motion = player.deltaMovement
            val motionStrength = Vec3(motion.x, 0.0, motion.z).length()
            ModuleGrimVelocity.attackQueue = ModuleGrimVelocity.getAttacks(motionStrength, strength.toDouble()) + 1

            if (debug) {
                chat("Target: ${entity?.name?.string ?: "null"}")
                chat("Attack: ${ModuleGrimVelocity.attackQueue}")
            }

            val targetEntity = entity ?: return@handler

            when (noXZMode) {
                AttackMode.OneTime -> {
                    var sentAttacks = 0
                    if (ModuleGrimVelocity.attackQueue > 0) {
                        while (ModuleGrimVelocity.attackQueue >= 1) {
                            interaction.attack(player, targetEntity)
                            player.swing(InteractionHand.MAIN_HAND)
                            player.deltaMovement = player.deltaMovement.multiply(0.6, 1.0, 0.6)
                            sentAttacks++
                            ModuleGrimVelocity.recordAttack()
                            ModuleGrimVelocity.attackQueue--
                        }
                        jump = true
                        if (debug) {
                            chat("${name} OneTime attacks executed: $sentAttacks")
                            if (sentAttacks == 0) {
                                chat("§7[GrimVelocity] OneTime blocked: window budget exhausted")
                            }
                        }
                        entity = null
                    }
                }

                AttackMode.PerTick -> {
                    if (player.isSprinting) {
                        if (ModuleGrimVelocity.attackQueue >= 1) {
                            sendPacketSilently(ServerboundAttackPacket(targetEntity.id))
                            player.deltaMovement = player.deltaMovement.multiply(0.6, 1.0, 0.6)
                            player.isSprinting = false
                            player.swing(InteractionHand.MAIN_HAND)
                            if (debug) {
                                chat("${name} PerTick attack executed, remaining: ${ModuleGrimVelocity.attackQueue - 1}")
                            }
                        }
                        ModuleGrimVelocity.attackQueue--
                    }
                }
            }
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || player == null) return@handler

        val packet = event.packet

        if (packet is ClientboundSetEntityMotionPacket) {
            if (packet.id != player.id || !shouldReduce(packet)) return@handler
            if (packet.movement.y < 0 || player.abilities.mayfly) return@handler

            if (!alink) {
                alink = true
                ModuleGrimVelocity.grimTicks = 0
                ModuleGrimVelocity.inBoundOpen = true
            }
        }

        if (alink) {
            if (noXZServerPos == null && entity != null) {
                noXZServerPos = entity!!.position()
            }

            if (entity != null) {
                if (packet is ClientboundMoveEntityPacket) {
                    val movedEntity = packet.getEntity(world)
                    if (movedEntity != null && movedEntity == entity) {
                        noXZServerPos?.let { serverPos ->
                            val dx = if (packet.hasPosition()) packet.xa / 4096.0 else 0.0
                            val dy = if (packet.hasPosition()) packet.ya / 4096.0 else 0.0
                            val dz = if (packet.hasPosition()) packet.za / 4096.0 else 0.0
                            noXZServerPos = serverPos.add(dx, dy, dz)
                        }
                    }
                }

                if (packet is ClientboundTeleportEntityPacket) {
                    if (packet.id == entity!!.id) {
                        noXZServerPos = packet.change.position
                    }
                }
            }

            if (packet is ClientboundMoveEntityPacket || packet is ClientboundTeleportEntityPacket) {
                return@handler
            }

            if (!event.isCancelled && canDelayPacket(packet)) {
                event.cancelEvent()
                inBound.add(packet)
            }
            return@handler
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (player == null || !alink) return@handler

        if (ModuleGrimVelocity.grimTicks >= maxTicks.toLong()) {
            if (debug) chat("Time Out")
            clear()
            ModuleGrimVelocity.grimTicks = 0
        } else {
            ModuleGrimVelocity.grimTicks++
        }

        val killAuraTarget = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target else null
        if (killAuraTarget == null) {
            clear()
            entity = null
            ModuleGrimVelocity.attackQueue = 0
            return@handler
        }

        if (checkAsyncTargets()) {
            entity = killAuraTarget
            clear()
            ready = true
            if (debug) chat("Aim Target")
        }

        if (entity == null && ModuleGrimVelocity.attackQueue > 0) {
            clear()
            entity = null
            ModuleGrimVelocity.attackQueue = 0
        }
    }

    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        if (player == null || mc.gui.screen() != null) return@handler

        if (alink) {
            event.directionalInput = DirectionalInput.FORWARDS
        }
        if (jump) {
            jump = false
            event.jump = true
        }
    }

    private fun shouldReduce(packet: ClientboundSetEntityMotionPacket): Boolean {
        if (!ModuleKillAura.running) return false
        val hLength = Vec3(packet.movement.x, 0.0, packet.movement.z).length()
        return hLength >= strength.toDouble()
    }

    private fun isAimedAt(target: Entity): Boolean {
        val rotation = RotationManager.currentRotation ?: player.rotation
        val eyes = player.eyePosition
        val box = target.boundingBox
        val closest = clipBoxClosestPoint(box, eyes)
        val toClosest = closest.subtract(eyes).normalize()
        return rotation.directionVector.dot(toClosest) > 0.99
    }

    private fun checkAsyncTargets(): Boolean {
        if (player == null || world == null) return false
        if (!ModuleKillAura.running) return false

        val target = ModuleKillAura.targetTracker.target ?: return false
        if (isAimedAt(target)) return false

        for (entity in world.entitiesForRendering()) {
            if (entity !is Player || entity == player || !entity.isAlive) continue

            val box = entity.dimensions.makeBoundingBox(entity.position())
            val closest = clipBoxClosestPoint(box, player.eyePosition)

            if (closest.distanceToSqr(player.eyePosition) <= 9.0 && entity.shouldBeAttacked()) {
                val rotTo = Rotation.lookingAt(closest, from = player.eyePosition)
                val currentDir = RotationManager.currentRotation?.directionVector ?: player.lookAngle
                val yawness = rotTo.directionVector.dot(currentDir)
                if (yawness > 0.99) {
                    rot = rotTo
                    return true
                }
            }
        }
        return false
    }

    /**
     * Custom bounding-box closest point calculation (avoids Optional<Vec3> API issues).
     */
    private fun clipBoxClosestPoint(box: net.minecraft.world.phys.AABB, point: Vec3): Vec3 {
        val x = point.x.coerceIn(box.minX, box.maxX)
        val y = point.y.coerceIn(box.minY, box.maxY)
        val z = point.z.coerceIn(box.minZ, box.maxZ)
        return Vec3(x, y, z)
    }

    private fun canDelayPacket(packet: Packet<*>): Boolean {
        return packet !is ClientboundPlayerPositionPacket
            && packet !is ClientboundMoveEntityPacket
            && packet !is ClientboundTeleportEntityPacket
    }
}
