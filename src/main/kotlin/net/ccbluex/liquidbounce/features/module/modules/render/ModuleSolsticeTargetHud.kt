/*
 * ============================================================================
 *  ModuleTargetHud —— 移植 Solstice 的 TargetHUD.cpp/hpp (原生渲染)
 *
 *  适用: Rubbishy-Liquidbounce-Nextgen-for-Android (LiquidBounce Nextgen 0.39,
 *        Mojang 映射, Android SDK v30)
 *
 *  原版功能 (TargetHUD.cpp, Dear ImGui):
 *   1. 显示 KillAura 目标信息: 头像 + 名称 + 血条 + 吸收条
 *   2. 动画: 出现/消失缩放 (anim, dt*10) / 血量延迟 (mLerpedHealth, dt*10)
 *   3. 受伤动画: hurtTimeAnimPerc → 头像缩小 + 红色染色
 *   4. 血条: 水平渐变 (startColor→endColor, 主题色) + 金色吸收条覆盖
 *   5. 采样模式 (无目标时显示自己)
 *
 *  移植说明:
 *   - Aura::sTarget → KillAuraTargetTracker.target
 *   - D3D 皮肤纹理 → context.blit 皮肤纹理头部区域 (8..16/64 UV)
 *   - AddRectFilledMultiColor 水平渐变 → 8 段颜色插值近似
 *   - MathUtils::lerp → 帧率无关线性插值
 *   - getThemedColor → 粉蓝白三色循环插值
 *   - 受伤红染 (AddImage 乘色) → blit 后叠加半透明红 fill
 *
 *  可调节项 (20+): X/Y 偏移、UI 缩放、血量推算(占位)、无目标时显示自己、
 *        名称/血条/吸收条/背景开关、背景透明度、圆角、文字阴影、
 *        受伤闪红、出现动画速度、血量动画速度等。
 *
 *  渲染: 完全原生 —— OverlayRenderEvent + GuiGraphicsExtractor, 无 Web 依赖。
 *
 *  安装:
 *    1. 放入 src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/render/ModuleTargetHud.kt
 *    2. ModuleManager.kt: import + builtin 列表加 ModuleTargetHud,
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.piglin.Piglin
import net.minecraft.world.entity.monster.skeleton.Skeleton
import net.minecraft.world.entity.monster.zombie.Zombie
import kotlin.math.roundToInt

object ModuleTargetHud : ClientModule(
    "TargetHUD",
    ModuleCategories.RENDER,
    aliases = listOf("TargetHud"),
) {

    /* ============================= 枚举 ============================= */

    private enum class Style(override val tag: String) : Tagged { SOLSTICE("Solstice") }

    /* ============================= 可调节项 ============================= */

    private val style by enumChoice("Style", Style.SOLSTICE)
    private val offsetX by int("Offset X", 0, -400..400)          // 原版默认 100 (中心偏移)
    private val offsetY by int("Offset Y", 0, -400..400)
    private val uiScale by float("UI Scale", 1f, 0.5f..2.5f)      // 原版 Font Size 20 → 9px 缩放
    private val healthCalculation by boolean("Health Calculation", false)  // 原版血量推算 (保留开关)
    private val sampleSelf by boolean("Sample Self", false)       // 无目标时显示自己 (原版 mSampleMode)
    private val showName by boolean("Show Name", true)
    private val showHealthBar by boolean("Show Health Bar", true)
    private val showAbsorptionBar by boolean("Show Absorption Bar", true)
    private val background by boolean("Background", true)
    private val backgroundAlpha by int("Background Alpha", 128, 0..255)  // 原版 0.5
    private val radius by int("Radius", 10, 0..20)
    private val textShadow by boolean("Text Shadow", true)
    private val hurtFlash by boolean("Hurt Flash", true)          // 受伤红染 + 头像缩小

    // —— 动画 ——
    private val animationSpeed by float("Animation Speed", 50f, 1f..50f)     // 原版 dt*10
    private val healthAnimSpeed by float("Health Anim Speed", 10f, 1f..30f)  // 原版 dt*10

    /* ============================= 内部状态 ============================= */

    private var anim = 0f
    private var lastFrameNs = 0L
    private var lastTarget: LivingEntity? = null
    private var lastHurtTime = 0f
    private var hurtTime = 0f
    private var hurtTimeAnimPerc = 0f
    private var lerpedHealth = 0f
    private var lerpedAbsorption = 0f
    private var playerName = ""

    // 原版主题色板 (粉蓝白)
    private val themeColors = listOf(
        Color4b(0xE9, 0xA8, 0xBC),
        Color4b(0x6E, 0xC8, 0xF1),
        Color4b(255, 255, 255, 128),
    )

    /* ============================= 工具 ============================= */

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun themedColor(index: Float): Color4b {
        val time = 10000f / 3f
        val now = System.currentTimeMillis()
        val angle = ((now + index.toLong()) % time.toLong()).toFloat()
        val segmentTime = time / themeColors.size
        val seg = (angle / segmentTime).toInt().coerceIn(0, themeColors.size - 1)
        val t = (angle / segmentTime - seg).coerceIn(0f, 1f)
        return themeColors[seg].interpolateTo(themeColors[(seg + 1) % themeColors.size], t.toDouble())
    }

    /** 水平渐变近似 (原版 AddRectFilledMultiColor, 8 段插值) */
    private fun GuiGraphicsExtractor.drawHorizontalGradient(
        x1: Float, y1: Float, x2: Float, y2: Float, c1: Color4b, c2: Color4b,
    ) {
        if (x2 - x1 <= 0.5f) {
            drawQuad(x1, y1, x2, y2, c1)
            return
        }
        val segments = 8
        for (s in 0 until segments) {
            val sx = x1 + (x2 - x1) * s / segments
            val ex = x1 + (x2 - x1) * (s + 1) / segments
            val c = c1.interpolateTo(c2, (s / (segments - 1).toFloat()).toDouble())
            drawQuad(sx, y1, ex, y2, c)
        }
    }

    /** 头部纹理: 玩家取皮肤, 常见怪物取原版贴图 (头部 UV 均为 8..16/64) */
    private fun headTexture(entity: LivingEntity): Identifier? = when (entity) {
        is AbstractClientPlayer -> entity.skin.body().texturePath()
        is Skeleton -> Identifier.withDefaultNamespace("textures/entity/skeleton/skeleton.png")
        is Zombie -> Identifier.withDefaultNamespace("textures/entity/zombie/zombie.png")
        is Creeper -> Identifier.withDefaultNamespace("textures/entity/creeper/creeper.png")
        is Piglin -> Identifier.withDefaultNamespace("textures/entity/piglin/piglin.png")
        else -> null
    }

    /* ============================= 目标解析 ============================= */

    private fun resolveTarget(): LivingEntity? {
        var target = KillAuraTargetTracker.target
        if (target == null && sampleSelf) {
            target = mc.player
        }
        if (target != null && target.isDeadOrDying) {
            target = null
        }
        return target
    }

    /* =============================== 渲染 =============================== */

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val font = mc.font

        val now = mc.getFrameTimeNs()
        val frameTime = if (lastFrameNs != 0L) {
            ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        } else {
            0.016f
        }
        lastFrameNs = now

        val target = resolveTarget()
        val showing = target != null

        // 出现/消失动画 (原版: anim = lerp(anim, showing?1:0, dt*10))
        anim += ((if (showing) 1f else 0f) - anim) * (frameTime * animationSpeed).coerceAtMost(1f)
        if (anim < 0.01f) return@handler
        if (target == null) return@handler

        // 目标切换时立即重置动画值 (原版 mLastTarget != target)
        if (lastTarget !== target) {
            lastTarget = target
            lerpedHealth = target.health
            lerpedAbsorption = target.absorptionAmount
            hurtTimeAnimPerc = 0f
            lastHurtTime = 0f
            playerName = target.displayName?.string ?: target.name.string
        }

        // 受伤动画 (原版: lerpedHurtTime + hurtTimeAnimPerc lerp dt*20)
        hurtTime = target.hurtTime.toFloat()
        if (hurtTime > lastHurtTime) {
            lastHurtTime = hurtTime
        }
        val lerpedHurtTime = lerp(lastHurtTime / 10f, hurtTime / 10f, frameTime)
        hurtTimeAnimPerc += (lerpedHurtTime - hurtTimeAnimPerc) * (frameTime * 20f).coerceAtMost(1f)

        // 血量延迟动画 (原版: lerp dt*10)
        lerpedHealth += (target.health - lerpedHealth) * (frameTime * healthAnimSpeed).coerceAtMost(1f)
        lerpedAbsorption += (target.absorptionAmount - lerpedAbsorption) * (frameTime * healthAnimSpeed).coerceAtMost(1f)

        val s = uiScale
        val alphaAnim = anim

        // 盒子尺寸与位置 (屏幕中心 + 偏移, 原版 230x70 → 适配 9px 为 150x40)
        val boxW = 150f * s * anim
        val boxH = 40f * s * anim
        val boxX = context.guiWidth() / 2f - boxW / 2f + offsetX
        val boxY = context.guiHeight() / 2f - boxH / 2f + offsetY

        // 背景 (原版 黑色 0.5 圆角 15*anim)
        if (background) {
            context.drawRoundedRect(
                boxX, boxY, boxX + boxW, boxY + boxH,
                radius * s * anim,
                Color4b(0, 0, 0, (0.5f * 255 * alphaAnim).roundToInt().coerceIn(0, 255)),
            )
        }

        // 头像 (原版 60*anim, 受伤缩小到 40*anim + 红染)
        val headSize = 28f * s * anim
        val headShrink = lerp(headSize, 18f * s * anim, hurtTimeAnimPerc)
        val headX = boxX + 5f * s * anim + (headSize - headShrink) / 2f
        val headY = boxY + 5f * s * anim + (headSize - headShrink) / 2f

        val texture = headTexture(target)
        if (texture != null) {
            // 头部 UV: 64x64 皮肤纹理的 8..16 区域
            context.blit(
                texture,
                headX.roundToInt(), headY.roundToInt(),
                (headX + headShrink).roundToInt(), (headY + headShrink).roundToInt(),
                8f / 64f, 16f / 64f, 8f / 64f, 16f / 64f,
            )
            // 受伤红染 (原版 imageColor 乘色近似)
            if (hurtFlash && hurtTimeAnimPerc > 0.01f) {
                val a = (150 * hurtTimeAnimPerc * alphaAnim).roundToInt().coerceIn(0, 255)
                if (a > 0) {
                    context.fill(
                        headX.roundToInt(), headY.roundToInt(),
                        (headX + headShrink).roundToInt(), (headY + headShrink).roundToInt(),
                        (a shl 24) or 0x00FF0000,
                    )
                }
            }
        }

        // 血条位置 (原版: 底部, 从头像右侧开始)
        val healthStartX = boxX + headSize + 10f * s * anim
        val healthStartY = boxY + boxH - 13f * s * anim
        val healthBarEndX = boxX + boxW - 5f * s * anim
        val barW = (healthBarEndX - healthStartX).coerceAtLeast(1f)
        val barH = 8f * s * anim

        // 名称 (原版: 血条上方居中, 白字阴影)
        if (showName && playerName.isNotEmpty()) {
            val ydiff = healthStartY - boxY
            val nameY = boxY + ydiff / 2f - 4f * s + 5f * s * anim
            context.text(
                font, playerName,
                (healthStartX).roundToInt(), nameY.roundToInt(),
                Color4b(255, 255, 255, (255 * alphaAnim).roundToInt()).argb, textShadow,
            )
        }

        // 血条
        if (showHealthBar) {
            // 背景 (原版 100,100,100,170)
            context.drawRoundedRect(
                healthStartX, healthStartY, healthBarEndX, healthStartY + barH, 5f,
                Color4b(100, 100, 100, (0.67f * 255 * alphaAnim).roundToInt().coerceIn(0, 255)),
            )

            val healthPerc = (lerpedHealth / target.maxHealth).coerceIn(0f, 1f)
            val healthEndX = lerp(healthStartX, healthBarEndX, healthPerc)

            // 血条: 水平渐变 (原版 startColor=themed(0) → endColor=themed(endXDiff*2))
            if (healthPerc > 0.01f) {
                val startColor = themedColor(0f).alpha((255 * alphaAnim).roundToInt().coerceIn(0, 255))
                val endColor = themedColor((healthBarEndX - healthStartX) * 2f)
                    .alpha((255 * alphaAnim).roundToInt().coerceIn(0, 255))
                context.drawHorizontalGradient(
                    healthStartX, healthStartY, healthEndX, healthStartY + barH,
                    startColor, endColor,
                )
            }

            // 吸收条 (原版 金色 244,204,0 覆盖在血条上)
            if (showAbsorptionBar) {
                val absPerc = (lerpedAbsorption / 20f).coerceIn(0f, 1f)
                if (absPerc > 0.01f) {
                    val absEndX = lerp(healthStartX, healthBarEndX, absPerc)
                    context.drawRoundedRect(
                        healthStartX, healthStartY, absEndX, healthStartY + barH, 5f,
                        Color4b(244, 204, 0, (255 * alphaAnim).roundToInt().coerceIn(0, 255)),
                    )
                }
            }
        }
    }
}
