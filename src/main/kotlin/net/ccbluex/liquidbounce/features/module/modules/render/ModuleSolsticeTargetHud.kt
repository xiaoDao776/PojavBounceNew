/*
 * ============================================================================
 *  ModuleSolsticeTargetHud —— Rise ModernTargetInfo 移植 (修正版)
 *  - 字体对齐修复
 *  - 去掉渐变条里多余的矩形块
 *  - 可调背景色 / 边框
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraTargetTracker
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.LivingEntity
import java.text.DecimalFormat
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object ModuleSolsticeTargetHud : ClientModule(
    "SolsticeTargetHud",
    ModuleCategories.RENDER,
    aliases = listOf("RiseTargetInfo"),
) {

    private enum class BackgroundMode(override val tag: String) : Tagged {
        GLASS("Glass"), TINT("Tint"), SOLID("Solid"), CUSTOM("Custom")
    }

    // —— 布局 ——
    private val targetInfoX by int("Position X", 40, 0..2000)
    private val targetInfoY by int("Position Y", 40, 0..1200)
    private val uiScale by float("UI Scale", 1f, 0.5f..2f)
    private val fontSize by int("Font Size", 11, 8..20)

    // —— 功能 ——
    private val particles by boolean("Particles", true)
    private val inWorld by boolean("In World", true)
    private val multiTarget by boolean("Multi Target", false)
    private val followPlayer by boolean("Follow Player", false)

    // —— 背景 / 边框 ——
    private val backgroundMode by enumChoice("Background Mode", BackgroundMode.CUSTOM)
    private val backgroundColor by color("Background Color", Color4b(18, 18, 24, 200))
    private val backgroundColor2 by color("Background Color 2", Color4b(28, 28, 36, 200))
    private val backgroundShade by color("Bar Shade", Color4b(40, 40, 48, 255))
    private val radius by int("Radius", 8, 0..20)

    private val border by boolean("Border", true)
    private val borderColor by color("Border Color", Color4b(0, 0, 0, 160))
    private val borderWidth by float("Border Width", 1.2f, 0.5f..4f)

    // —— 文字 / 强调色 ——
    private val textColor by color("Text Color", Color4b(255, 255, 255, 255))
    private val accentColor by color("Accent Color", Color4b(0x6E, 0xC8, 0xF1, 255))
    private val textShadow by boolean("Text Shadow", true)

    /* ============================= 内部状态 ============================= */

    private val EDGE = 8f
    private val PAD = 6f
    private val hpFormat = DecimalFormat("0.0")

    private var destinationY = 4f
    private val stackAnimation = Anim(::easeOutExpo, 1150)
    private val openingAnimation = Anim(::easeOutElastic, 500)
    private val healthAnimation = Anim(::easeOutQuint, 250)

    private var panelHeight = 0f
    private var lastSeenMs = 0L
    private var lastTarget: LivingEntity? = null

    private data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float)
    private val particleList = mutableListOf<Particle>()

    private val themeColors = listOf(
        Color4b(0xE9, 0xA8, 0xBC),
        Color4b(0x6E, 0xC8, 0xF1),
        Color4b(255, 255, 255, 128),
    )

    /* ============================= 动画 ============================= */

    private class Anim(var easing: (Float) -> Float, var duration: Long) {
        private var startTime = System.currentTimeMillis()
        private var startValue = 0f
        private var targetValue = 0f

        fun run(target: Float) {
            if (target == targetValue) return
            startValue = getValue()
            targetValue = target
            startTime = System.currentTimeMillis()
        }

        fun getValue(): Float {
            val progress = ((System.currentTimeMillis() - startTime).toFloat() / duration).coerceIn(0f, 1f)
            return startValue + (targetValue - startValue) * easing(progress)
        }
    }

    private fun easeOutExpo(x: Float): Float = if (x >= 1f) 1f else 1f - 2f.pow(-10f * x)
    private fun easeInBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return c3 * x * x * x - c1 * x * x
    }
    private fun easeOutElastic(x: Float): Float {
        val c4 = (2 * PI) / 3
        return when {
            x == 0f -> 0f
            x == 1f -> 1f
            else -> (2f.pow(-10f * x) * sin((x * 10f - 0.75f) * c4) + 1f).toFloat()
        }
    }
    private fun easeOutSine(x: Float): Float = sin(x * PI / 2).toFloat()
    private fun easeOutQuint(x: Float): Float = 1f - (1f - x).pow(5)

    private fun themedColor(index: Float): Color4b {
        val time = 10000f / 3f
        val now = System.currentTimeMillis()
        val angle = ((now + index.toLong()) % time.toLong()).toFloat()
        val segmentTime = time / themeColors.size
        val seg = (angle / segmentTime).toInt().coerceIn(0, themeColors.size - 1)
        val t = (angle / segmentTime - seg).coerceIn(0f, 1f)
        return themeColors[seg].interpolateTo(themeColors[(seg + 1) % themeColors.size], t.toDouble())
    }

    /* ============================= 绘制工具 ============================= */

    /** 纯圆角背景，无内部矩形块 */
    private fun GuiGraphicsExtractor.drawPanelBg(
        x: Float, y: Float, w: Float, h: Float, r: Float, c1: Color4b, c2: Color4b,
    ) {
        if (w <= 0f || h <= 0f) return
        val rad = r.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
        // 只用一层圆角底，避免渐变分段产生方块感
        drawRoundedRect(x, y, x + w, y + h, rad, c1)
        // 顶部轻微高光（很薄一条，仍圆角）
        if (c1.argb != c2.argb) {
            val topH = (h * 0.35f).coerceAtMost(14f)
            drawRoundedRect(x, y, x + w, y + topH, rad, c2.alpha((c2.a * 0.45f).toInt().coerceIn(0, 255)))
        }
    }

    /**
     * 文字：先 translate 到像素对齐位置，再 scale。
     * 避免 scale 后再用浮点坐标导致错位。
     */
    private fun GuiGraphicsExtractor.drawTextAligned(
        font: Font, str: String, x: Float, y: Float,
        color: Color4b, scale: Float, shadow: Boolean,
    ) {
        val ix = x.roundToInt().toFloat()
        val iy = y.roundToInt().toFloat()
        pose().withPush {
            translate(ix, iy)
            if (scale != 1f) scale(scale, scale)
            text(font, str, 0, 0, color.argb, shadow)
        }
    }

    private fun textW(font: Font, text: String, scale: Float): Float = font.width(text) * scale

    /**
     * 获取皮肤 Identifier。
     * 玩家优先用 PlayerSkin.texture()（完整皮肤图），
     * 其次 body().texturePath()，最后 DefaultPlayerSkin。
     */
    private fun resolveSkinId(entity: LivingEntity): Identifier {
        if (entity is AbstractClientPlayer) {
            val skin = entity.skin
            // 1.21+ PlayerSkin.texture()
            runCatching {
                val m = skin.javaClass.methods.firstOrNull {
                    it.name == "texture" && it.parameterCount == 0
                }
                val id = m?.invoke(skin) as? Identifier
                if (id != null) return id
            }
            runCatching {
                val body = skin.javaClass.methods.firstOrNull { it.name == "body" && it.parameterCount == 0 }
                    ?.invoke(skin)
                val path = body?.javaClass?.methods?.firstOrNull {
                    it.name == "texturePath" && it.parameterCount == 0
                }?.invoke(body) as? Identifier
                if (path != null) return path
            }
            return DefaultPlayerSkin.get(entity.uuid).texture()
        }
        // 非玩家：按实体类型选默认贴图
        val path = when (entity.type.toString()) {
            "entity.minecraft.zombie", "entity.minecraft.zombie_villager" ->
                "textures/entity/zombie/zombie.png"
            "entity.minecraft.skeleton", "entity.minecraft.stray" ->
                "textures/entity/skeleton/skeleton.png"
            "entity.minecraft.creeper" ->
                "textures/entity/creeper/creeper.png"
            "entity.minecraft.piglin", "entity.minecraft.zombified_piglin" ->
                "textures/entity/piglin/piglin.png"
            "entity.minecraft.enderman" ->
                "textures/entity/enderman/enderman.png"
            else -> "textures/entity/player/wide/steve.png"
        }
        return Identifier.withDefaultNamespace(path)
    }

    /**
     * 绘制头部正面（含帽子层）。
     * 使用像素 UV：头 8,8 尺寸 8x8；帽 40,8 尺寸 8x8；纹理 64x64。
     * （之前用 8/64 归一化坐标会导致采样错误 → 发白）
     */
    private fun GuiGraphicsExtractor.drawEntityHead(
        entity: LivingEntity,
        x: Float,
        y: Float,
        size: Float,
    ) {
        val ix = x.roundToInt()
        val iy = y.roundToInt()
        val s = size.roundToInt().coerceAtLeast(4)

        if (entity is AbstractClientPlayer) {
            // 官方 PlayerFaceRenderer：自动画头 + 帽子
            runCatching {
                PlayerFaceRenderer.draw(this, entity.skin, ix, iy, s)
                return
            }
            runCatching {
                PlayerFaceRenderer.draw(this, resolveSkinId(entity), ix, iy, s)
                return
            }
        }

        val tex = resolveSkinId(entity)
        // 兼容多种 blit 签名：像素 UV (u,v,regionW,regionH,texW,texH)
        val drawn = runCatching {
            // 形式 A: blit(id, x, y, w, h, u, v, regionW, regionH, texW, texH)
            val m = this.javaClass.methods.firstOrNull { method ->
                method.name == "blit" && method.parameterCount >= 10
            }
            if (m != null) {
                // 头
                m.invoke(this, tex, ix, iy, s, s, 8f, 8f, 8, 8, 64, 64)
                // 帽（半透明由管线处理；失败忽略）
                runCatching { m.invoke(this, tex, ix, iy, s, s, 40f, 8f, 8, 8, 64, 64) }
                true
            } else false
        }.getOrDefault(false)

        if (!drawn) {
            // 形式 B: 现有 Solstice 用的 (id, x1,y1,x2,y2, u0,v0,u1,v1) —— 改用正确归一化 UV
            runCatching {
                val m = this.javaClass.methods.firstOrNull { method ->
                    method.name == "blit" && method.parameterCount == 9
                } ?: return@runCatching
                // 头: u0=8/64, v0=8/64, u1=16/64, v1=16/64
                m.invoke(
                    this, tex,
                    ix, iy, ix + s, iy + s,
                    8f / 64f, 8f / 64f, 16f / 64f, 16f / 64f,
                )
                runCatching {
                    m.invoke(
                        this, tex,
                        ix, iy, ix + s, iy + s,
                        40f / 64f, 8f / 64f, 48f / 64f, 16f / 64f,
                    )
                }
            }
        }
    }

    private fun GuiGraphicsExtractor.drawDropShadow(x: Float, y: Float, size: Float, rad: Float) {
        for (i in 1..3) {
            val spread = i * 1.8f
            val a = (16 * (1 - i / 3.5f)).roundToInt().coerceAtLeast(0)
            drawRoundedRect(
                x - spread, y - spread, x + size + spread, y + size + spread,
                rad + spread * 0.4f, Color4b(0, 0, 0, a),
            )
        }
    }

    /* ============================= 粒子 ============================= */

    private fun spawnParticles(centerX: Float, centerY: Float, hurtTime: Float) {
        val count = (hurtTime * Math.random() / 2).toInt()
        repeat(count) {
            particleList += Particle(
                centerX, centerY,
                ((Math.random() - 0.5) * 1.7f).toFloat(),
                ((Math.random() - 0.5) * 1.7f).toFloat(),
                1f,
            )
        }
        while (particleList.size > 200) particleList.removeAt(0)
    }

    private fun GuiGraphicsExtractor.renderParticles() {
        val it = particleList.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx; p.y += p.vy
            p.vx *= 0.95f; p.vy *= 0.95f
            p.life -= 0.04f
            if (p.life <= 0f) { it.remove(); continue }
            drawQuad(
                p.x, p.y, p.x + 2f, p.y + 2f,
                Color4b(255, 255, 255, (p.life * 255).roundToInt().coerceIn(0, 255)),
            )
        }
    }

    /* =============================== 主渲染 =============================== */

    private fun GuiGraphicsExtractor.render(
        x: Float, y: Float, target: LivingEntity, s: Float, tickDelta: Float,
    ) {
        val ctx = this
        val now = System.currentTimeMillis()
        val out = !inWorld || now - lastSeenMs > 1000L
        openingAnimation.easing = if (out) ::easeInBack else ::easeOutElastic
        openingAnimation.duration = if (out) 400 else 850
        openingAnimation.run(if (out) 0f else 1f)

        val scale = openingAnimation.getValue()
        if (scale <= 0.01f) return

        val name = target.displayName?.string ?: target.name.string
        val font = mc.font
        // mc.font 默认约 9px，统一缩放
        val ts = (fontSize / 9f) * s

        val health = (if (!inWorld) 0f else target.health).coerceAtMost(target.maxHealth)
        val healthText = hpFormat.format(health.toDouble())
        val nameW = textW(font, name, ts)
        val hpTextW = textW(font, healthText, ts)
        val healthBarW = maxOf(nameW + 28f * s - hpTextW, 70f * s)

        healthAnimation.easing = ::easeOutQuint
        healthAnimation.duration = 250
        healthAnimation.run((health / target.maxHealth.coerceAtLeast(0.01f)) * healthBarW)
        val healthFill = healthAnimation.getValue()

        val hurtTime = (if (target.hurtTime == 0) 0f else target.hurtTime - tickDelta) * 0.5f
        val face = 28f * s
        val faceOff = hurtTime / 2f
        val panelW = EDGE + face + PAD + healthBarW + PAD + hpTextW + EDGE
        val panelH = face + EDGE * 2
        panelHeight = panelH

        // —— 背景色 ——
        var bg1 = backgroundColor
        var bg2 = backgroundColor2
        var accent = accentColor
        when (backgroundMode) {
            BackgroundMode.GLASS -> {
                bg1 = Color4b(0, 0, 0, 100)
                bg2 = Color4b(0, 0, 0, 80)
            }
            BackgroundMode.TINT -> {
                val t1 = themedColor((x + y) / 10f)
                val t2 = themedColor((x + y + panelH) / 10f)
                bg1 = Color4b(t1.r / 5, t1.g / 5, t1.b / 5, 140)
                bg2 = Color4b(t2.r / 5, t2.g / 5, t2.b / 5, 140)
                accent = t1
            }
            BackgroundMode.SOLID -> {
                val t = themedColor(0f)
                bg1 = t.alpha(150)
                bg2 = themedColor(40f).alpha(150)
                accent = Color4b(255, 255, 255)
            }
            BackgroundMode.CUSTOM -> {
                bg1 = backgroundColor
                bg2 = backgroundColor2
            }
        }

        // 以面板中心缩放
        val cx = x + panelW / 2f
        val cy = y + panelH / 2f

        pose().withPush {
            translate(cx, cy)
            scale(scale, scale)
            translate(-cx, -cy)

            val rr = radius * s * 1.1f

            // 背景（无分段方块）
            ctx.drawPanelBg(x, y, panelW, panelH, rr, bg1, bg2)

            // 边框
            if (border) {
                ctx.drawRoundedRect(
                    x, y, x + panelW, y + panelH, rr,
                    Color4b.TRANSPARENT, borderColor, borderWidth,
                )
            }

            // 受伤红底
            val headX = x + EDGE + faceOff
            val headY = y + EDGE + faceOff
            val headSize = (face - hurtTime).coerceAtLeast(4f)
            if (hurtTime > 0f) {
                ctx.drawRoundedRect(
                    headX, headY, headX + headSize, headY + headSize,
                    radius * s * 0.6f,
                    Color4b(255, 0, 0, (hurtTime / 9 * 200).roundToInt().coerceIn(0, 200)),
                )
            }

            // 头像阴影 + 皮肤正面（含帽子）
            ctx.drawDropShadow(headX, headY, headSize, radius * s * 0.5f)
            ctx.drawEntityHead(target, headX, headY, headSize)
            if (hurtTime > 0f) {
                val a = (hurtTime / 9 * 120).roundToInt().coerceIn(0, 120)
                if (a > 0) {
                    ctx.fill(
                        headX.roundToInt(), headY.roundToInt(),
                        (headX + headSize).roundToInt(), (headY + headSize).roundToInt(),
                        (a shl 24) or 0x00FF0000,
                    )
                }
            }

            // 名称
            val textX = x + EDGE + face + PAD
            val nameY = y + EDGE + 2f * s
            ctx.drawTextAligned(font, "Name:", textX, nameY, textColor.alpha(200), ts * 0.9f, textShadow)
            val labelW = textW(font, "Name:", ts * 0.9f)
            ctx.drawTextAligned(
                font, name,
                textX + labelW + 3f * s, nameY,
                accent.alpha(255), ts, textShadow,
            )

            // 血条
            val barX = textX
            val barH = 5f * s
            val barY = y + EDGE + face - barH - 2f * s
            ctx.drawRoundedRect(barX, barY, barX + healthBarW, barY + barH, 2.5f * s, backgroundShade)
            if (healthFill > 0.5f) {
                ctx.drawRoundedRect(
                    barX, barY, barX + healthFill, barY + barH, 2.5f * s,
                    accent.alpha(255),
                )
            }

            // 血量数字（与血条垂直居中对齐）
            val hpY = barY + (barH - 9f * ts) / 2f
            ctx.drawTextAligned(
                font, healthText,
                barX + healthBarW + 4f * s, hpY,
                accent.alpha(255), ts, textShadow,
            )
        }

        renderParticles()
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val context = event.context
        val tickDelta = event.tickDelta
        val s = uiScale
        val now = System.currentTimeMillis()

        var x = targetInfoX.toFloat()
        var y = targetInfoY.toFloat()

        if (mc.gui.screen() is ChatScreen) {
            context.render(x, y, mc.player ?: return@handler, s, tickDelta)
            return@handler
        }

        val target = KillAuraTargetTracker.target
        if (target == null) {
            if (now - lastSeenMs > 1400L) return@handler
            val fading = lastTarget ?: return@handler
            context.render(x, y, fading, s, tickDelta)
            return@handler
        }
        lastTarget = target
        lastSeenMs = now

        if (multiTarget) {
            val targets = KillAuraTargetTracker.targets()
            if (targets.isEmpty()) return@handler
            var count = 0
            for (i in targets.indices) {
                if (count > 2) break
                val t = targets[i]
                val rect = WorldToScreen.calculateScreenRect(t.boundingBox)
                if (followPlayer && rect == null) continue
                destinationY = if (i <= 0) 0f else 60f
                if (followPlayer && rect != null) {
                    x = rect.x2
                    y = rect.y2 - (rect.y2 - rect.y1) / 2f - panelHeight / 2f
                }
                context.render(x, y, t, s, tickDelta)
                if (!followPlayer) {
                    y += stackAnimation.let {
                        it.easing = ::easeOutExpo
                        it.duration = 1150
                        it.run(destinationY)
                        it.getValue()
                    }
                }
                count++
            }
        } else {
            context.render(x, y, target, s, tickDelta)
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> { _ ->
        if (!particles) return@handler
        val target: LivingEntity? = if (mc.gui.screen() is ChatScreen) {
            mc.player
        } else {
            KillAuraTargetTracker.target
        }
        if (target == null || target.hurtTime <= 0) return@handler
        spawnParticles(targetInfoX + 20f, targetInfoY + 20f, target.hurtTime * 0.5f)
    }
}
