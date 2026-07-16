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

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.GrimVelocityMode
import net.ccbluex.liquidbounce.features.module.modules.combat.grimvelocity.ModuleGrimVelocity
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.world.scaffold.ModuleScaffold
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.math.multiply
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.network.handlePacket
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Reduce mode: Complex anti-knockback with 4 sub-components:
 * - AirPush: Attack nearest entity to use air friction
 * - AttackReduce: Extra attack to reduce knockback
 * - Buffer: Delay velocity packets for timed release
 * - JumpReset: Jump towards target to cancel knockback
 */
@Suppress("unused", "MemberVisibilityCanBePrivate", "MagicNumber")
object GrimVelocityReduce : GrimVelocityMode("Reduce") {

    enum class ReduceComponent(override val tag: String) : Tagged {
        AirPush("AirPush"),
        AttackReduce("AttackReduce"),
        Buffer("Buffer"),
        JumpReset("JumpReset"),
    }

    enum class TargetPoint(override val tag: String) : Tagged {
        Smart("Smart"),
        Opposite("Opposite"),
    }

    val components by multiEnumChoice("Components", ReduceComponent.AirPush, ReduceComponent.AttackReduce, ReduceComponent.Buffer, ReduceComponent.JumpReset)
    val attackRange by float("AttackRange", 3f, 0f..6f)
    val throughWallRange by float("ThroughWallRange", 0f, 0f..6f)
    val debugMessage by boolean("DebugMessage", false)

    // Buffer settings
    val bufferMaxTicks by int("BufferMaxTicks", 50, 1..500)
    val bufferAggressive by boolean("DelayLonger", false)

    // AirPush settings
    val airPushRisk by boolean("RiskMode", false)

    // JumpReset settings
    val jumpResetPoint by enumChoice("TargetPoint", TargetPoint.Smart)

    // State variables
    var reduceHandleVelocity = false
    private var reduceNeedHandleRotation = false
    private var reduceAttackCount = -1
    private var reduceTicksSinceVelocity = -1
    private var reduceBuffering = false
    private var reduceBufferTicks = -1
    private var reduceQueuedRotation = false
    private var reduceHandleJumpReset = false
    private var reducePendingDebugAttackReduce = false
    private var reducePendingDebugAirPush = false
    private val reduceBufferedPackets = LinkedBlockingQueue<Packet<*>>()
    private var reduceFinalRot = Rotation(0f, 0f)
    private var reduceFinalDiff = 0f

    // Rotation
    private var rot = Rotation(0f, 0f)

    override fun enable() {
        reduceNeedHandleRotation = false
        reduceAttackCount = -1
        reduceTicksSinceVelocity = -1
        reduceHandleVelocity = false
        reduceBuffering = false
        reduceBufferTicks = -1
        reduceBufferedPackets.clear()
        reduceFinalRot = Rotation(0f, 0f)
        reduceFinalDiff = 0f
        reduceHandleJumpReset = false
        reducePendingDebugAttackReduce = false
        reducePendingDebugAirPush = false
        reduceQueuedRotation = false
        rot = Rotation(0f, 0f)
    }

    override fun disable() {
        if (player == null) return
        reduceNeedHandleRotation = false
        reduceAttackCount = -1
        reduceTicksSinceVelocity = -1
        reduceHandleVelocity = false
        if (reduceBuffering) {
            reduceReleaseBufferedPackets()
        }
        reduceBuffering = false
        reduceBufferTicks = -1
        reduceBufferedPackets.clear()
        reduceFinalRot = Rotation(0f, 0f)
        reduceFinalDiff = 0f
        reduceHandleJumpReset = false
        reducePendingDebugAttackReduce = false
        reducePendingDebugAirPush = false
        reduceQueuedRotation = false
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (player == null) return@handler
        handleReduceTick()
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || player == null || world == null) return@handler

        if (ModuleGrimVelocity.pause > 0) return@handler

        val packet = event.packet

        if (packet is ClientboundSetEntityMotionPacket) {
            if (packet.id != player.id) return@handler

            val velocityX = packet.movement.x
            val velocityY = packet.movement.y
            val velocityZ = packet.movement.z

            val falling = velocityY <= 0.0
            val hStrength = Vec3(velocityX, 0.0, velocityZ).length()
            val lowStrength = hStrength < 0.1
            val inFluid = player.isInWater || player.isInLava

            if (falling || lowStrength || player.onClimbable() || inFluid) return@handler

            reduceAttackCount = computeReduceTicks((packet.movement.x * 8000.0).toInt(), (packet.movement.z * 8000.0).toInt())
            if (debugMessage) {
                chat("Knockback received, estimated processing time: $reduceAttackCount ${if (reduceAttackCount <= 1) "tick" else "ticks"}")
            }
            if (debugMessage && components.contains(ReduceComponent.AttackReduce)) {
                reducePendingDebugAttackReduce = true
            }
            if (debugMessage && components.contains(ReduceComponent.AirPush)) {
                reducePendingDebugAirPush = true
            }

            if (components.contains(ReduceComponent.Buffer) && !reduceBuffering) {
                reduceBuffering = true
                reduceBufferTicks = 0
                reduceQueuedRotation = components.contains(ReduceComponent.JumpReset)
                event.cancelEvent()
                reduceBufferedPackets.add(packet)
            } else if (!reduceBuffering) {
                reduceHandleVelocity = true
                reduceTicksSinceVelocity = 0
                reduceNeedHandleRotation = components.contains(ReduceComponent.JumpReset)
                if (components.contains(ReduceComponent.JumpReset)) {
                    reduceComputeJumpReset(velocityX, velocityZ)
                }
            }
        }

        if (reduceBuffering) {
            if (canDelayPacket(packet) && !event.isCancelled) {
                event.cancelEvent()
                reduceBufferedPackets.add(packet)
            }
        }
    }

    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        handleReduceMovement(event)
    }

    fun isReduceCantKeepSprint(): Boolean {
        return reduceHandleVelocity || !reduceBufferedPackets.isEmpty()
    }

    fun handleReduceClick() {
        if (player == null) return
        if (!reduceHandleVelocity) return

        val sprinting = player.isSprinting || player.wasSprinting
        val killAuraTarget = if (ModuleKillAura.running) ModuleKillAura.targetTracker.target else null

        if (components.contains(ReduceComponent.AttackReduce)) {
            if (killAuraTarget != null && sprinting && isAimedAt(killAuraTarget)) {
                if (ModuleGrimVelocity.attackAndLock()) {
                    sendPacketSilently(
                        ServerboundAttackPacket(killAuraTarget.id)
                    )
                    ModuleGrimVelocity.recordAttack()
                    player.swing(InteractionHand.MAIN_HAND)
                    player.deltaMovement = player.deltaMovement.multiply(0.6, 1.0, 0.6)
                    player.isSprinting = false

                    if (reducePendingDebugAttackReduce) {
                        chat("AttackReduce: ${killAuraTarget.name.string}")
                        reducePendingDebugAttackReduce = false
                    }
                }
            }
        }

        if (components.contains(ReduceComponent.AirPush)) {
            if (killAuraTarget == null && sprinting) {
                val illegal = reduceGetClosestIllegalPlayerEntity()
                if (illegal != null) {
                    if (ModuleGrimVelocity.attackAndLock()) {
                        sendPacketSilently(
                            ServerboundAttackPacket(illegal.id)
                        )
                        player.swing(InteractionHand.MAIN_HAND)
                        if (interaction.localPlayerMode != GameType.SPECTATOR) {
                            player.deltaMovement = player.deltaMovement.multiply(0.6, 1.0, 0.6)
                            player.isSprinting = false
                        }
                        if (reducePendingDebugAirPush) {
                            chat("Pushed back, set position at: (${player.blockX} ${player.blockY} ${player.blockZ})")
                            reducePendingDebugAirPush = false
                        }
                    }
                }
            }
        }
    }

    private fun handleReduceTick() {
        if (player == null) return

        if (reduceTicksSinceVelocity >= 0) {
            reduceTicksSinceVelocity++
            if (reduceTicksSinceVelocity >= 10) {
                reduceTicksSinceVelocity = -1
                reduceHandleVelocity = false
                reduceHandleJumpReset = false
            }
            if (reduceTicksSinceVelocity >= reduceAttackCount) {
                reduceHandleVelocity = false
            }
        }

        if (reduceBufferTicks >= 0) {
            reduceBufferTicks++
        }

        if (!reduceBuffering) return

        val killAura = if (ModuleKillAura.running) ModuleKillAura else null

        var shouldFlush = killAura == null || (
            reduceBufferTicks >= 0 && (
                reduceBufferTicks >= bufferMaxTicks || player.onGround()
            )
        )

        if (bufferAggressive) {
            shouldFlush = killAura == null || (
                reduceBufferTicks >= 0 && (
                    ((player.isSprinting || player.wasSprinting) && reduceBufferTicks >= bufferMaxTicks)
                    || reduceCantSprint()
                )
            )
        }

        if (killAura == null) shouldFlush = true

        if (shouldFlush) {
            if (debugMessage) {
                chat("Releasing buffered packets, total time: $reduceBufferTicks ${if (reduceBufferTicks <= 1) "tick" else "ticks"}")
            }
            reduceTicksSinceVelocity = 0
            reduceHandleVelocity = true
            reduceReleaseBufferedPackets()
            reduceBufferTicks = -1
            reduceBuffering = false
        }
    }

    private fun reduceReleaseBufferedPackets() {
        while (!reduceBufferedPackets.isEmpty()) {
            val packet = reduceBufferedPackets.poll() ?: continue
            if (packet is ClientboundSetEntityMotionPacket && packet.id == player.id) {
                val velocityX = packet.movement.x
                val velocityZ = packet.movement.z
                if (reduceQueuedRotation) {
                    reduceNeedHandleRotation = true
                    reduceQueuedRotation = false
                }
                reduceComputeJumpReset(velocityX, velocityZ)
            }
            handlePacket(packet)
        }
    }

    private fun reduceComputeJumpReset(velocityX: Double, velocityZ: Double) {
        if (player == null || world == null) return

        val knockback = Vec3(velocityX, 0.0, velocityZ)
        val oppositeDir = knockback.normalize().let { Vec3(-it.x, 0.0, -it.z) }
        val oppositeVec = player.position().add(oppositeDir.x, 0.0, oppositeDir.z)
        val oppositeRotation = Rotation.lookingAt(oppositeVec, from = player.eyePosition)

        val targetYaw = oppositeRotation.yaw
        val eye = player.eyePosition
        val worldInst: Level = world

        val atkRange = attackRange.toDouble()
        val throughRange = throughWallRange.toDouble()

        // Gather nearby players within attack range
        val accepted = mutableListOf<Player>()
        for (entity in world.entitiesForRendering()) {
            if (entity !is Player || entity == player || !entity.isAlive) continue
            if (player.distanceToSqr(entity) <= atkRange * atkRange) {
                accepted.add(entity)
            }
        }

        var bestVec: Vec3? = null
        var bestYawDiff = Float.MAX_VALUE

        for (entity in accepted) {
            val box: AABB = entity.boundingBox
            val nearest = clipBoxClosestPoint(box, eye)

            // Check nearest point
            val nearestDist = eye.distanceTo(nearest)
            if (nearestDist <= atkRange) {
                if (nearestDist <= throughRange || isNotBlocked(worldInst, eye, nearest)) {
                    val rotTo = Rotation.lookingAt(nearest, from = eye)
                    val yawDiff = Mth.wrapDegrees(rotTo.yaw - targetYaw)
                    if (abs(yawDiff) < abs(bestYawDiff)) {
                        bestYawDiff = yawDiff
                        bestVec = nearest
                    }
                }
            }

            // Search inside the bounding box
            var xSearch = 0.15
            while (xSearch < 0.85) {
                var ySearch = 0.15
                while (ySearch < 1.0) {
                    var zSearch = 0.15
                    while (zSearch < 0.85) {
                        val sample = Vec3(
                            box.minX + (box.maxX - box.minX) * xSearch,
                            box.minY + (box.maxY - box.minY) * ySearch,
                            box.minZ + (box.maxZ - box.minZ) * zSearch
                        )

                        val dist = eye.distanceTo(sample)
                        if (dist > atkRange) { zSearch += 0.1; continue }
                        if (dist > throughRange && !isNotBlocked(worldInst, eye, sample)) {
                            zSearch += 0.1; continue
                        }

                        val rotTo = Rotation.lookingAt(sample, from = eye)
                        val yawDiff = Mth.wrapDegrees(rotTo.yaw - targetYaw)
                        if (abs(yawDiff) < abs(bestYawDiff)) {
                            bestYawDiff = yawDiff
                            bestVec = sample
                        }
                        zSearch += 0.1
                    }
                    ySearch += 0.1
                }
                xSearch += 0.1
            }
        }

        if (bestVec != null && jumpResetPoint == TargetPoint.Smart) {
            reduceFinalRot = Rotation.lookingAt(bestVec, from = eye)
            reduceFinalDiff = bestYawDiff
        } else {
            reduceFinalRot = Rotation(
                targetYaw,
                RotationManager.currentRotation?.pitch ?: player.xRot
            )
            reduceFinalDiff = 0f
        }
    }

    fun computeMovementForward(targetRotation: Rotation, rotationDifference: Float): Pair<Float, Float> {
        val currentYaw = RotationManager.currentRotation?.yaw ?: player.yRot
        var deltaYaw = Mth.wrapDegrees(targetRotation.yaw - currentYaw)

        if (rotationDifference > 22.5f) {
            deltaYaw -= 45f
        } else if (rotationDifference < -22.5f) {
            deltaYaw += 45f
        }

        val radian = Math.toRadians(deltaYaw.toDouble())
        val x = kotlin.math.sin(radian)
        val z = kotlin.math.cos(radian)

        val forward = when {
            z > 0.707 -> 1f
            z < -0.707 -> -1f
            else -> 0f
        }
        val strafe = when {
            x > 0.707 -> -1f
            x < -0.707 -> 1f
            else -> 0f
        }

        return Pair(forward, strafe)
    }

    private fun reduceGetClosestIllegalPlayerEntity(): Player? {
        if (player == null || world == null) return null

        var target: Player? = null
        var closestDistance = Double.MAX_VALUE

        for (entity in world.entitiesForRendering()) {
            if (entity !is Player || entity == player || !entity.isAlive) continue
            val dist = player.distanceTo(entity)
            if (dist < 11 && !airPushRisk) continue
            if (dist < closestDistance) {
                closestDistance = dist.toDouble()
                target = entity
            }
        }
        return target
    }

    private fun computeReduceTicks(motionX: Int, motionZ: Int): Int {
        val kb = hypot(motionX.toDouble(), motionZ.toDouble())
        val y = 0.000408163 * kb + 0.7142857
        val result = kotlin.math.round(y).toInt()
        return result.coerceIn(1, 5)
    }

    private fun reduceCantSprint(): Boolean {
        if (player == null) return true
        if (player.hasEffect(MobEffects.BLINDNESS)) return true
        if (player.hasEffect(MobEffects.SLOWNESS)) return true
        if (player.isCrouching) return true
        if (player.pose == Pose.SWIMMING && !player.isInWater) return true
        return player.vehicle == null && !player.abilities.mayfly && player.foodData.foodLevel <= 6f
    }

    private fun isNotBlocked(world: Level, start: Vec3, end: Vec3): Boolean {
        val rayVec = end.subtract(start)
        val context = ClipContext(
            start, end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            CollisionContext.of(player)
        )
        val hitResult = world.clip(context)
        return hitResult.type == HitResult.Type.MISS
    }

    private fun isAimedAt(target: Entity): Boolean {
        val rotation = RotationManager.currentRotation ?: player.rotation
        val eyes = player.eyePosition
        val box = target.boundingBox
        val closest = clipBoxClosestPoint(box, eyes)
        val toClosest = closest.subtract(eyes).normalize()
        return rotation.directionVector.dot(toClosest) > 0.99
    }

    private fun canDelayPacket(packet: Packet<*>): Boolean {
        return packet !is ClientboundPlayerPositionPacket
    }

    fun handleReduceRotation() {
        if (player == null || !reduceNeedHandleRotation) return
        if (player.onGround() && abs(reduceFinalDiff) <= 45f) {
            rot = reduceFinalRot
            if (debugMessage) {
                chat("Successfully reset, difference: %.2f".format(reduceFinalDiff))
            }
        }
        reduceNeedHandleRotation = false
    }

    fun handleReduceMovement(event: MovementInputEvent) {
        if (player == null) return

        val scaffoldEnabled = ModuleScaffold.running

        if (mc.gui.screen() == null && (reduceHandleVelocity || reduceBuffering)) {
            event.directionalInput = DirectionalInput.FORWARDS
        }

        if (!scaffoldEnabled) {
            if (reduceTicksSinceVelocity >= 0
                && components.contains(ReduceComponent.JumpReset)
                && reduceTicksSinceVelocity < 4
                && abs(reduceFinalDiff) <= 45f
                && player.onGround()
            ) {
                reduceHandleJumpReset = true
                event.jump = true
            } else if (reduceHandleJumpReset && reduceTicksSinceVelocity >= 4) {
                reduceHandleJumpReset = false
            }

            if (reduceHandleJumpReset) {
                val (forward, strafe) = computeMovementForward(reduceFinalRot, reduceFinalDiff)
                if (forward != 0f || strafe != 0f) {
                    event.directionalInput = DirectionalInput(forward, strafe)
                }
            }
        } else if (reduceHandleJumpReset) {
            reduceHandleJumpReset = false
        }
    }

    private fun clipBoxClosestPoint(box: AABB, point: Vec3): Vec3 {
        val x = point.x.coerceIn(box.minX, box.maxX)
        val y = point.y.coerceIn(box.minY, box.maxY)
        val z = point.z.coerceIn(box.minZ, box.maxZ)
        return Vec3(x, y, z)
    }
}
