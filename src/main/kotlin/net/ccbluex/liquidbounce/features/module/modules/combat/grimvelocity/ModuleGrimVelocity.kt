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

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.InputHandleEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TickPacketProcessEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.combat.attackEntity
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.player.Player
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Port of Natsuki's Velocity module and its Vanilla/Legit submodules.
 */
@Suppress("TooManyFunctions")
object ModuleGrimVelocity : ClientModule("GrimVelocity", ModuleCategories.COMBAT, aliases = listOf("GrimKB")) {

    private val antiCheat by enumChoice("AntiCheat", AntiCheat.VANILLA).apply(::tagBy)

    private object Vanilla : ValueGroup("Vanilla") {
        val horizontal by float("Horizontal", 0f, 0f..100f, "%")
        val vertical by float("Vertical", 0f, 0f..100f, "%")
    }

    private object Legit : ValueGroup("Legit") {
        object Delay : ToggleableValueGroup(ModuleGrimVelocity, "Delay", false) {
            val untilLanding by boolean("UntilLanding", false)
            val untilSprint by boolean("UntilSprint", false)
            val releaseMode by enumChoice("ReleaseMode", ReleaseMode.RELEASE_ALL)
            val maxTicks by int("MaxLimitedTick", 20, 0..40, "ticks")
        }

        object JumpReset : ToggleableValueGroup(ModuleGrimVelocity, "JumpReset", false) {
            val rotations = tree(RotationsValueGroup(this, MovementCorrection.SILENT))
        }

        object Reduce : ToggleableValueGroup(ModuleGrimVelocity, "Reduce", false) {
            val attackCountMode by enumChoice("AttackCountMode", AttackCountMode.CUSTOM)
            val count by int("Count", 5, 1..5)
            val attackRange by float("AttackRange", 3f, 0f..6f)
            val throughWallsRange by float("ThroughWallsRange", 0f, 0f..6f)
        }

        init {
            tree(Delay)
            tree(JumpReset)
            tree(Reduce)
        }
    }

    init {
        tree(Vanilla)
        tree(Legit)
    }

    private var attackCount = -1
    private var ticksSinceVelocity = -1
    private var handleVelocity = false
    private var needHandleRotation = false
    private var targetRotation = Rotation.ZERO
    private var handleJumpReset = false
    private var buffering = false
    private var bufferTicks = -1
    private var releasePending = false
    private var holdingSprintEntityData = false

    override fun onEnabled() = reset(flush = false)

    override fun onDisabled() = reset(flush = true)

    private fun reset(flush: Boolean) {
        if (flush && buffering) BlinkManager.flush(TransferOrigin.INCOMING)
        attackCount = -1
        ticksSinceVelocity = -1
        handleVelocity = false
        needHandleRotation = false
        handleJumpReset = false
        buffering = false
        bufferTicks = -1
        releasePending = false
        holdingSprintEntityData = false
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || !event.original) return@handler

        when (val packet = event.packet) {
            is ClientboundPlayerPositionPacket -> {
                if (buffering) BlinkManager.flush(TransferOrigin.INCOMING)
                reset(flush = false)
            }

            is ClientboundSetEntityMotionPacket if packet.id == player.id -> {
                if (antiCheat == AntiCheat.VANILLA) {
                    applyVanilla(packet)
                } else if (isValidVelocity(packet)) {
                    receiveLegitVelocity(packet)
                }
            }
        }
    }

    private fun applyVanilla(packet: ClientboundSetEntityMotionPacket) {
        packet.movement.x *= Vanilla.horizontal / 100f
        packet.movement.y *= Vanilla.vertical / 100f
        packet.movement.z *= Vanilla.horizontal / 100f
    }

    private fun receiveLegitVelocity(packet: ClientboundSetEntityMotionPacket) {
        attackCount = if (Legit.Reduce.enabled && Legit.Reduce.attackCountMode == AttackCountMode.CUSTOM) {
            Legit.Reduce.count
        } else {
            computeReduceTicks(packet.movement.x, packet.movement.z)
        }
        targetRotation = Rotation.fromRotationVec(-packet.movement.x, 0.0, -packet.movement.z)

        if (Legit.Delay.enabled && !cantSprint()) {
            if (!buffering) {
                buffering = true
                bufferTicks = 0
            }
            return
        }
        startVelocityHandling()
    }

    @Suppress("unused")
    private val blinkHandler = handler<BlinkPacketEvent> { event ->
        if (antiCheat == AntiCheat.LEGIT && buffering && event.origin == TransferOrigin.INCOMING) {
            event.action = BlinkManager.Action.QUEUE
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (antiCheat != AntiCheat.LEGIT) return@handler

        updateVelocityTimer()
        updateDelay()
        updateJumpResetRotation()
    }

    @Suppress("unused")
    private val inputHandleHandler = handler<InputHandleEvent> {
        if (antiCheat == AntiCheat.LEGIT) attackReduceTarget()
    }

    private fun updateVelocityTimer() {
        if (ticksSinceVelocity < 0) return

        ticksSinceVelocity++
        if (ticksSinceVelocity >= 10 || ticksSinceVelocity >= attackCount) handleVelocity = false
        if (ticksSinceVelocity >= 10) ticksSinceVelocity = -1
    }

    private fun updateDelay() {
        if (!buffering) return

        if (holdingSprintEntityData) {
            if (!handleVelocity) {
                BlinkManager.flush(TransferOrigin.INCOMING)
                buffering = false
                holdingSprintEntityData = false
            }
            return
        }

        bufferTicks++
        if (shouldReleaseDelay()) releasePending = true
    }

    @Suppress("unused")
    private val packetProcessHandler = handler<TickPacketProcessEvent> {
        if (!releasePending) return@handler

        buffering = false
        releasePending = false
        bufferTicks = -1

        if (Legit.Delay.releaseMode == ReleaseMode.ENTITY_DATA_DELAY && flushUntilSprintEntityData()) {
            buffering = true
            holdingSprintEntityData = true
        } else {
            BlinkManager.flush(TransferOrigin.INCOMING)
        }
        startVelocityHandling()
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (antiCheat != AntiCheat.LEGIT) return@handler

        if (buffering || Legit.Reduce.enabled && handleVelocity) {
            event.directionalInput = event.directionalInput.copy(forwards = true, backwards = false)
        }
        applyJumpResetInput(event)
    }

    private fun applyJumpResetInput(event: MovementInputEvent) {
        if (!Legit.JumpReset.enabled || ticksSinceVelocity !in 0..3) {
            if (ticksSinceVelocity !in 0..3) handleJumpReset = false
            return
        }

        val currentRotation = RotationManager.currentRotation ?: player.rotation
        if (player.onGround()) {
            event.jump = true
            handleJumpReset = true
        }
        if (handleJumpReset) {
            event.directionalInput = movementFor(targetRotation.yaw, currentRotation.yaw, 0f)
        }
    }

    private fun updateJumpResetRotation() {
        if (!Legit.JumpReset.enabled || !needHandleRotation) return

        val currentRotation = RotationManager.currentRotation ?: player.rotation
        if (player.onGround()) {
            RotationManager.setRotationTarget(
                targetRotation.copy(pitch = currentRotation.pitch),
                considerInventory = false,
                valueGroup = Legit.JumpReset.rotations,
                priority = Priority.IMPORTANT_FOR_PLAYER_LIFE,
                provider = this
            )
        }
        needHandleRotation = false
    }

    private fun attackReduceTarget() {
        if (!Legit.Reduce.enabled || !handleVelocity || !player.isSprinting) return

        val rotation = RotationManager.currentRotation ?: player.rotation
        val range = maxOf(Legit.Reduce.attackRange, Legit.Reduce.throughWallsRange)
        val target = findEntityInCrosshair(range.toDouble(), rotation) {
            it is Player && !it.isRemoved && it != player
        }?.entity as? Player ?: return

        val distance = player.distanceTo(target)
        val inNormalRange = player.hasLineOfSight(target) && distance <= Legit.Reduce.attackRange
        if (!inNormalRange && distance > Legit.Reduce.throughWallsRange) return

        attackEntity(target, SwingMode.DO_NOT_HIDE, keepSprint = false)
        player.deltaMovement = player.deltaMovement.multiply(0.6, 1.0, 0.6)
        player.isSprinting = false
    }

    private fun startVelocityHandling() {
        handleVelocity = true
        ticksSinceVelocity = 0
        needHandleRotation = Legit.JumpReset.enabled
    }

    private fun isValidVelocity(packet: ClientboundSetEntityMotionPacket): Boolean {
        val movement = packet.movement
        return movement.y > 0.0 && hypot(movement.x, movement.z) >= 0.1 &&
            !player.onClimbable() && !player.isInWater && !player.isInLava
    }

    private fun cantSprint(): Boolean = player.hasEffect(MobEffects.BLINDNESS) ||
        player.hasEffect(MobEffects.SLOWNESS) || player.isShiftKeyDown || player.isCrouching ||
        !player.isPassenger && !player.abilities.mayfly && player.foodData.foodLevel <= 6

    private fun computeReduceTicks(motionX: Double, motionZ: Double): Int {
        val packetStrength = hypot(motionX, motionZ) * 8000.0
        return (0.000408163 * packetStrength + 0.7142857).roundToInt().coerceIn(1, 5)
    }

    private fun shouldReleaseDelay(): Boolean {
        if (bufferTicks >= Legit.Delay.maxTicks || cantSprint()) return true

        return when {
            Legit.Delay.untilLanding && Legit.Delay.untilSprint -> player.onGround() && player.isSprinting
            Legit.Delay.untilLanding -> player.onGround()
            Legit.Delay.untilSprint -> player.isSprinting
            else -> false
        }
    }

    private fun flushUntilSprintEntityData(): Boolean {
        var foundSprintEntityData = false
        BlinkManager.flush { snapshot ->
            if (snapshot.origin != TransferOrigin.INCOMING || foundSprintEntityData) return@flush false
            if (isStoppedSprintingPacket(snapshot.packet)) {
                foundSprintEntityData = true
                return@flush false
            }
            true
        }
        return foundSprintEntityData
    }

    private fun isStoppedSprintingPacket(packet: Packet<*>): Boolean {
        if (packet !is ClientboundSetEntityDataPacket || packet.id != player.id) return false

        return packet.packedItems.any { data ->
            data.id == ENTITY_FLAGS_INDEX && data.value is Byte &&
                (data.value as Byte).toInt() and (1 shl SPRINTING_FLAG) == 0
        }
    }

    private fun movementFor(targetYaw: Float, currentYaw: Float, difference: Float): DirectionalInput {
        var deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw)
        if (difference > 22.5f) deltaYaw -= 45f
        if (difference < -22.5f) deltaYaw += 45f

        val radians = Math.toRadians(deltaYaw.toDouble())
        return DirectionalInput(
            movementForward = when {
                cos(radians) > 0.707 -> 1f
                cos(radians) < -0.707 -> -1f
                else -> 0f
            },
            movementSideways = when {
                sin(radians) > 0.707 -> -1f
                sin(radians) < -0.707 -> 1f
                else -> 0f
            }
        )
    }

    private enum class AntiCheat(override val tag: String) : Tagged {
        VANILLA("Vanilla"),
        LEGIT("Legit"),
    }

    private enum class AttackCountMode(override val tag: String) : Tagged {
        CUSTOM("Custom"),
        CALCULATE("Calculate"),
    }

    private enum class ReleaseMode(override val tag: String) : Tagged {
        RELEASE_ALL("ReleaseAll"),
        ENTITY_DATA_DELAY("EntityDataDelay"),
    }

    private const val ENTITY_FLAGS_INDEX = 0
    private const val SPRINTING_FLAG = 3
}
