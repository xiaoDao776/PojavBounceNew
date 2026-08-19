/*
 * ============================================================================
 *  ModuleRiseClickgui —— 还原 Rise click.zip (RiseClickGUI + UIColors + ModuleComponent)
 *
 *  适用: LiquidBounce Nextgen 0.39 · 原生 Overlay · 无 Web
 *
 *  原版对照 (com.alan.clients.ui.click.standard):
 *   - 单窗口 position ≈ 400×300 / 416×338，居中可拖
 *   - UIColors: BACKGROUND(23,26,33) SECONDARY(18,20,25) TEXT/SECONDARY_TEXT/TRINARY/OVERLAY
 *   - scaleAnimation + opacityAnimation: EASE_OUT_EXPO 开 / 关加快
 *   - 侧边栏 SidebarCategory + 模块列表 ModuleComponent
 *   - 左键开关 / 右键展开设置
 *   - 圆角 + dropShadow 近似
 *   - Boolean / Number / Enum / Color 设置行
 *
 *  DropdownClickGUI 为另一套多面板风格；本模块以 Standard RiseClickGUI 为主。
 * ============================================================================
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.events.MouseScrollEvent
import net.ccbluex.liquidbounce.event.events.MouseScrollInHotbarEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.drawQuad
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object ModuleRiseClickgui : ClientModule(
    "[Beta]RiseClickGui",
    ModuleCategories.RENDER,
    bind = GLFW.GLFW_KEY_RIGHT_SHIFT,
    aliases = listOf("RiseClickGUI", "RiseGUI"),
) {

    /* —— Rise UIColors —— */
    private val colBg by color("Background", Color4b(23, 26, 33, 254))
    private val colSecondary by color("Secondary", Color4b(18, 20, 25, 255))
    private val colText by color("Text", Color4b(255, 255, 255, 255))
    private val colSecondaryText by color("Secondary Text", Color4b(255, 255, 255, 220))
    private val colTrinaryText by color("Trinary Text", Color4b(255, 255, 255, 130))
    private val colOverlay by color("Overlay", Color4b(0, 0, 0, 50))
    private val colAccent by color("Accent", Color4b(0x56, 0xB4, 0xE9, 255))

    private val windowW by float("Window Width", 400f, 280f..560f)
    private val windowH by float("Window Height", 300f, 200f..480f)
    private val sidebarW by float("Sidebar Width", 90f, 60f..140f)
    private val round by float("Round", 10f, 0f..20f)
    private val moduleRowH by float("Module Row H", 22f, 16f..32f)
    private val settingRowH by float("Setting Row H", 18f, 14f..28f)
    private val animMs by float("Anim Ms", 300f, 80f..800f)
    private val shadow by boolean("Drop Shadow", true)
    private val scaleAnim by boolean("Scale Animation", true)

    /* —— 状态 —— */
    private var winX = -1f
    private var winY = -1f
    private var dragging = false
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var selectedCat: ModuleCategory? = null
    private var expanded: ClientModule? = null
    private var scroll = 0f
    private var targetScroll = 0f
    private var mouseX = 0f
    private var mouseY = 0f
    private var sliderDrag: RangedValue<*>? = null

    private var scale = 0f
    private var opacity = 0f
    private var lastNs = 0L

    private val enumOpen = IdentityHashMap<Value<*>, Boolean>()

    private val palette = listOf(
        Color4b(0x56, 0xB4, 0xE9), Color4b(255, 70, 70), Color4b(90, 230, 110),
        Color4b(255, 170, 40), Color4b(140, 110, 255), Color4b(255, 120, 200),
        Color4b(255, 255, 255), Color4b(40, 40, 40),
    )
    private var colorEdit: Value<*>? = null
    private var paletteX = 0f
    private var paletteY = 0f

    /* —— 缓动 EASE_OUT_EXPO —— */
    private fun easeOutExpo(t: Float): Float =
        if (t >= 1f) 1f else (1.0 - 2.0.pow((-10.0 * t))).toFloat()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun guiMX() =
        (mc.mouseHandler.xpos() * mc.window.guiScaledWidth / mc.window.width).toFloat()

    private fun guiMY() =
        (mc.mouseHandler.ypos() * mc.window.guiScaledHeight / mc.window.height).toFloat()

    private fun over(x: Float, y: Float, w: Float, h: Float) =
        mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h

    private fun modulesIn(cat: ModuleCategory) =
        ModuleManager.getModules().filter { it.category == cat && !it.hidden }

    private fun collectValues(mod: ClientModule): List<Value<*>> = try {
        mod.collectValuesRecursively().toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun getActual(v: Value<*>): Any? {
        var o: Any? = try { v.get() } catch (_: Exception) { null }
        var d = 0
        while (o is Value<*> && d < 5) {
            o = try { o.get() } catch (_: Exception) { null }
            d++
        }
        return o
    }

    private fun trySet(v: Value<*>, value: Any) {
        try {
            v.javaClass.methods.firstOrNull { it.name == "set" && it.parameterCount == 1 }
                ?.invoke(v, value)
        } catch (_: Exception) {}
    }

    /* —— 输入层 Screen —— */
    private class RiseGuiScreen : Screen(Component.literal("RiseClickGui")) {
        override fun isPauseScreen() = false
        override fun shouldCloseOnEsc() = false
        override fun keyPressed(event: KeyEvent): Boolean {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                ModuleRiseClickgui.enabled = false
            }
            return true
        }
        override fun mouseClicked(event: net.minecraft.client.input.MouseButtonEvent, doubleClick: Boolean): Boolean = true
        override fun mouseReleased(event: net.minecraft.client.input.MouseButtonEvent): Boolean = true
        override fun mouseDragged(event: net.minecraft.client.input.MouseButtonEvent, dx: Double, dy: Double): Boolean = true
        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean = true
    }

    private fun openLayer() {
        if (mc.gui.screen() is RiseGuiScreen) return
        try { mc.gui.setScreen(RiseGuiScreen()) } catch (_: Throwable) {
            try { mc.execute { mc.gui.setScreen(RiseGuiScreen()) } } catch (_: Throwable) {}
        }
    }

    private fun closeLayer() {
        if (mc.gui.screen() is RiseGuiScreen) {
            try { mc.gui.setScreen(null) } catch (_: Throwable) {
                try { mc.execute { mc.gui.setScreen(null) } } catch (_: Throwable) {}
            }
        }
    }

    override suspend fun enabledEffect() {
        selectedCat = ModuleCategories.entries.firstOrNull()
        expanded = null
        scroll = 0f
        targetScroll = 0f
        scale = 0f
        opacity = 0f
        winX = -1f
        openLayer()
    }

    override fun onDisabled() {
        closeLayer()
        sliderDrag = null
        colorEdit = null
    }

    @Suppress("unused")
    private val rotHandler = handler<MouseRotationEvent> { e ->
        if (enabled || scale > 0.01f) e.cancelEvent()
    }

    @Suppress("unused")
    private val hotbarHandler = handler<MouseScrollInHotbarEvent> { e ->
        if (enabled || scale > 0.01f) e.cancelEvent()
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { e ->
        if (!enabled && scale < 0.01f) return@handler
        if (e.keyCode == GLFW.GLFW_KEY_ESCAPE && e.action == 1) {
            enabled = false
        }
    }

    @Suppress("unused")
    private val scrollHandler = handler<MouseScrollEvent> { e ->
        if (!enabled) return@handler
        val contentX = winX + sidebarW
        if (over(contentX, winY + 28f, windowW - sidebarW, windowH - 28f)) {
            targetScroll = (targetScroll - e.vertical.toFloat() * 22f).coerceAtLeast(0f)
        }
    }

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { e ->
        if (!enabled || scale < 0.05f) return@handler
        if (mc.gui.screen() !is RiseGuiScreen) openLayer()
        mouseX = guiMX()
        mouseY = guiMY()

        if (e.action == 0) {
            dragging = false
            sliderDrag = null
            return@handler
        }
        if (e.action != 1) return@handler

        // 调色板
        colorEdit?.let { cv ->
            if (e.button == 0) {
                val cell = 14f
                if (over(paletteX, paletteY, 4 * cell, 2 * cell)) {
                    val c = ((mouseX - paletteX) / cell).toInt().coerceIn(0, 3)
                    val r = ((mouseY - paletteY) / cell).toInt().coerceIn(0, 1)
                    trySet(cv, palette[(r * 4 + c).coerceIn(0, palette.lastIndex)])
                }
                colorEdit = null
            }
            return@handler
        }

        // 拖标题栏
        if (e.button == 0 && over(winX, winY, windowW, 28f)) {
            dragging = true
            dragOffX = mouseX - winX
            dragOffY = mouseY - winY
            return@handler
        }

        // 侧边栏分类
        val cats = ModuleCategories.entries
        var cy = winY + 36f
        for (cat in cats) {
            if (over(winX + 4f, cy, sidebarW - 8f, 20f)) {
                if (e.button == 0) {
                    selectedCat = cat
                    expanded = null
                    targetScroll = 0f
                    scroll = 0f
                }
                return@handler
            }
            cy += 22f
        }

        // 模块列表
        val cat = selectedCat ?: return@handler
        val listX = winX + sidebarW + 6f
        val listY0 = winY + 32f - scroll
        val clipTop = winY + 28f
        val clipBot = winY + windowH - 6f
        var y = listY0
        for (mod in modulesIn(cat)) {
            if (y + moduleRowH > clipTop && y < clipBot) {
                if (over(listX, y, windowW - sidebarW - 14f, moduleRowH)) {
                    when (e.button) {
                        0 -> if (mod.name != name) mod.enabled = !mod.enabled
                        1 -> if (collectValues(mod).isNotEmpty()) {
                            expanded = if (expanded == mod) null else mod
                        }
                    }
                    return@handler
                }
            }
            y += moduleRowH
            if (expanded == mod) {
                for (v in collectValues(mod)) {
                    if (y + settingRowH > clipTop && y < clipBot) {
                        if (over(listX, y, windowW - sidebarW - 14f, settingRowH)) {
                            handleSettingClick(v, listX, windowW - sidebarW - 14f, e.button)
                            return@handler
                        }
                    }
                    y += settingRowH
                    if (enumOpen[v] == true) {
                        val actual = getActual(v)
                        if (actual is Enum<*>) {
                            val constants = actual.javaClass.enumConstants ?: emptyArray()
                            for (c in constants) {
                                if (y + settingRowH > clipTop && y < clipBot) {
                                    if (over(listX, y, windowW - sidebarW - 14f, settingRowH) && e.button == 0) {
                                        trySet(v, c)
                                        return@handler
                                    }
                                }
                                y += settingRowH
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleSettingClick(v: Value<*>, x: Float, w: Float, button: Int) {
        val actual = getActual(v) ?: return
        when {
            actual is Boolean && button == 0 -> trySet(v, !actual)
            actual is Enum<*> && button == 0 -> enumOpen[v] = !(enumOpen[v] ?: false)
            actual is Number && v is RangedValue<*> && button == 0 -> {
                sliderDrag = v
                applySlider(v, x + w * 0.4f, x + w - 8f)
            }
            actual.javaClass.simpleName.contains("Color", true) && button == 0 -> {
                colorEdit = if (colorEdit == v) null else v
                paletteX = (x + w - 4 * 14f).coerceAtLeast(4f)
                paletteY = (mouseY + 8f)
            }
        }
    }

    private fun applySlider(v: RangedValue<*>, x1: Float, x2: Float) {
        val minV = (v.range.start as? Number)?.toFloat() ?: 0f
        val maxV = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
        val nv = ((mouseX - x1) / (x2 - x1).coerceAtLeast(1f) * (maxV - minV) + minV).coerceIn(minV, maxV)
        when (getActual(v)) {
            is Float -> trySet(v, nv)
            is Double -> trySet(v, nv.toDouble())
            is Int -> trySet(v, nv.roundToInt())
            is Long -> trySet(v, nv.toLong())
        }
    }

    /* —— 渲染 —— */
    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val ctx = event.context
        val font = mc.font
        val now = System.nanoTime()
        val dt = if (lastNs != 0L) ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f) else 0.016f
        lastNs = now

        // 动画目标
        val open = enabled
        val speed = if (open) (1000f / animMs) else (1000f / (animMs * 0.35f))
        scale = (scale + (if (open) 1f else 0f - scale) * (dt * speed).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        // 用 expo 重映射观感
        val sVis = if (scaleAnim) easeOutExpo(scale) else if (open) 1f else 0f
        opacity = lerp(opacity, if (open) 1f else 0f, (dt * speed).coerceIn(0f, 1f))
        if (sVis < 0.01f && !open) return@handler

        mouseX = guiMX()
        mouseY = guiMY()
        val sw = ctx.guiWidth().toFloat()
        val sh = ctx.guiHeight().toFloat()
        if (winX < 0f) {
            winX = sw / 2f - windowW / 2f
            winY = sh / 2f - windowH / 2f
        }
        if (dragging) {
            winX = (mouseX - dragOffX).coerceIn(0f, sw - windowW)
            winY = (mouseY - dragOffY).coerceIn(0f, sh - windowH)
        }
        scroll += (targetScroll - scroll) * 0.28f

        sliderDrag?.let { applySlider(it, winX + sidebarW + 6f + (windowW - sidebarW - 14f) * 0.4f, winX + windowW - 14f) }

        val a = (255 * opacity * sVis.coerceAtLeast(0.15f)).roundToInt().coerceIn(0, 255)
        val cx = winX + windowW / 2f
        val cy = winY + windowH / 2f

        // 全屏遮罩
        ctx.drawQuad(0f, 0f, sw, sh, Color4b(0, 0, 0, (40 * opacity).roundToInt()))

        ctx.pose().withPush {
            if (scaleAnim && sVis < 0.999f) {
                translate(cx * (1f - sVis), cy * (1f - sVis))
                // 等价于 translate(center*(1-s)) + scale(s)：中心缩放
                translate(cx, cy)
                scale(sVis, sVis)
                translate(-cx, -cy)
            }

            // dropShadow
            if (shadow && sVis > 0.7f) {
                for (i in 1..5) {
                    val e = i * 2.2f
                    val sa = (12 * opacity * (1f - i / 6f)).roundToInt()
                    ctx.drawRoundedRect(winX - e, winY - e, winX + windowW + e, winY + windowH + e, round + 2f, Color4b(0, 0, 0, sa))
                }
            }

            // 主背景 BACKGROUND
            ctx.drawRoundedRect(winX, winY, winX + windowW, winY + windowH, round, colBg.alpha(a))

            // 侧边栏 SECONDARY
            ctx.drawRoundedRect(winX, winY, winX + sidebarW, winY + windowH, round, colSecondary.alpha(a))
            // 盖住侧栏右侧圆角
            ctx.drawQuad(winX + sidebarW - 8f, winY, winX + sidebarW, winY + windowH, colSecondary.alpha(a))

            // 标题
            ctx.text(font, "Rise", (winX + 12f).roundToInt(), (winY + 10f).roundToInt(), colText.alpha(a).argb, true)

            // 分类
            val cats = ModuleCategories.entries
            var catY = winY + 36f
            for (cat in cats) {
                val sel = cat == selectedCat
                if (sel) {
                    ctx.drawRoundedRect(winX + 4f, catY - 1f, winX + sidebarW - 4f, catY + 19f, 4f, colAccent.alpha((a * 0.35f).toInt()))
                }
                if (over(winX + 4f, catY, sidebarW - 8f, 20f)) {
                    ctx.drawRoundedRect(winX + 4f, catY - 1f, winX + sidebarW - 4f, catY + 19f, 4f, colOverlay.alpha((a * 0.5f).toInt()))
                }
                val tc = if (sel) colAccent.alpha(a) else colTrinaryText.alpha(a)
                ctx.text(font, cat.tag, (winX + 12f).roundToInt(), (catY + 4f).roundToInt(), tc.argb, false)
                catY += 22f
            }

            // 内容区模块
            val cat = selectedCat ?: cats.firstOrNull()
            if (cat != null) {
                val listX = winX + sidebarW + 6f
                val listW = windowW - sidebarW - 14f
                val clipTop = winY + 28f
                val clipBot = winY + windowH - 6f
                var y = clipTop - scroll

                // 内容高度
                var contentH = 0f
                for (m in modulesIn(cat)) {
                    contentH += moduleRowH
                    if (expanded == m) {
                        for (v in collectValues(m)) {
                            contentH += settingRowH
                            if (enumOpen[v] == true) {
                                val act = getActual(v)
                                if (act is Enum<*>) contentH += settingRowH * (act.javaClass.enumConstants?.size ?: 0)
                            }
                        }
                    }
                }
                targetScroll = targetScroll.coerceIn(0f, max(0f, contentH - (windowH - 34f)))

                for (mod in modulesIn(cat)) {
                    val rowBot = y + moduleRowH
                    if (rowBot > clipTop && y < clipBot) {
                        val y1 = max(y, clipTop)
                        val y2 = min(rowBot, clipBot)
                        if (mod.enabled) {
                            ctx.drawQuad(listX, y1, listX + listW, y2, colAccent.alpha((a * 0.18f).toInt()))
                        }
                        if (over(listX, y1, listW, y2 - y1)) {
                            ctx.drawQuad(listX, y1, listX + listW, y2, colOverlay.alpha(a))
                        }
                        val ty = y + moduleRowH / 2f - 4f
                        if (ty >= clipTop && ty + 9f <= clipBot) {
                            val mc_ = if (mod.enabled) colText.alpha(a) else colSecondaryText.alpha(a)
                            ctx.text(font, mod.name, (listX + 6f).roundToInt(), ty.roundToInt(), mc_.argb, false)
                            if (collectValues(mod).isNotEmpty()) {
                                val mark = if (expanded == mod) "▾" else "▸"
                                ctx.text(font, mark, (listX + listW - 12f).roundToInt(), ty.roundToInt(), colTrinaryText.alpha(a).argb, false)
                            }
                        }
                    }
                    y += moduleRowH

                    if (expanded == mod) {
                        for (v in collectValues(mod)) {
                            if (y + settingRowH > clipTop && y < clipBot) {
                                drawSetting(ctx, font, v, listX, y, listW, a, clipTop, clipBot)
                            }
                            y += settingRowH
                            if (enumOpen[v] == true) {
                                val act = getActual(v)
                                if (act is Enum<*>) {
                                    for (c in act.javaClass.enumConstants ?: emptyArray()) {
                                        if (y + settingRowH > clipTop && y < clipBot) {
                                            val y1 = max(y, clipTop)
                                            val y2 = min(y + settingRowH, clipBot)
                                            ctx.drawQuad(listX, y1, listX + listW, y2, colSecondary.alpha((a * 0.6f).toInt()))
                                            if (c == act) {
                                                ctx.drawQuad(listX, y1, listX + 2f, y2, colAccent.alpha(a))
                                            }
                                            val ty = y + settingRowH / 2f - 4f
                                            if (ty >= clipTop && ty + 9f <= clipBot) {
                                                ctx.text(
                                                    font, c.name,
                                                    (listX + 14f).roundToInt(), ty.roundToInt(),
                                                    colSecondaryText.alpha(a).argb, false,
                                                )
                                            }
                                        }
                                        y += settingRowH
                                    }
                                }
                            }
                        }
                    }
                    if (y > clipBot + 40f) break
                }

                // 顶/底遮罩防止溢出
                ctx.drawQuad(winX + sidebarW, winY, winX + windowW, clipTop, colBg.alpha(a))
                ctx.drawQuad(winX + sidebarW, clipBot, winX + windowW, winY + windowH, colBg.alpha(a))
            }

            // 调色板
            colorEdit?.let {
                val cell = 14f
                ctx.drawRoundedRect(paletteX, paletteY, paletteX + 4 * cell + 6f, paletteY + 2 * cell + 6f, 4f, colSecondary.alpha(a))
                for (i in palette.indices) {
                    val c = i % 4
                    val r = i / 4
                    ctx.drawRoundedRect(
                        paletteX + 3 + c * cell, paletteY + 3 + r * cell,
                        paletteX + 3 + c * cell + cell - 2, paletteY + 3 + r * cell + cell - 2,
                        3f, palette[i].alpha(a),
                    )
                }
            }
        }
    }

    private fun drawSetting(
        ctx: GuiGraphicsExtractor, font: Font, v: Value<*>,
        x: Float, y: Float, w: Float, a: Int, clipTop: Float, clipBot: Float,
    ) {
        val actual = getActual(v) ?: return
        val y1 = max(y, clipTop)
        val y2 = min(y + settingRowH, clipBot)
        ctx.drawQuad(x, y1, x + w, y2, colSecondary.alpha((a * 0.45f).toInt()))
        if (over(x, y1, w, y2 - y1)) {
            ctx.drawQuad(x, y1, x + w, y2, colOverlay.alpha(a))
        }
        val ty = y + settingRowH / 2f - 4f
        if (ty < clipTop || ty + 9f > clipBot) return

        when {
            actual is Boolean -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
                val bx = x + w - 28f
                ctx.drawRoundedRect(bx, y + 4f, bx + 20f, y + settingRowH - 4f, 4f,
                    if (actual) colAccent.alpha(a) else Color4b(60, 60, 70, a))
                val kx = if (actual) bx + 10f else bx + 2f
                ctx.drawRoundedRect(kx, y + 5f, kx + 8f, y + settingRowH - 5f, 3f, Color4b.WHITE.alpha(a))
            }
            actual is Enum<*> -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
                ctx.text(
                    font, actual.name,
                    (x + w - 8f - font.width(actual.name)).roundToInt(), ty.roundToInt(),
                    colAccent.alpha(a).argb, false,
                )
            }
            actual is Number && v is RangedValue<*> -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), (y + 2f).roundToInt(), colSecondaryText.alpha(a).argb, false)
                val minV = (v.range.start as? Number)?.toFloat() ?: 0f
                val maxV = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
                val fv = actual.toFloat()
                val p = if (maxV > minV) ((fv - minV) / (maxV - minV)).coerceIn(0f, 1f) else 0f
                val sx = x + w * 0.4f
                val sw = w * 0.55f
                val sy = y + settingRowH - 6f
                ctx.drawQuad(sx, sy, sx + sw, sy + 2f, Color4b(50, 50, 60, a))
                ctx.drawQuad(sx, sy, sx + sw * p, sy + 2f, colAccent.alpha(a))
                val vs = if (fv == fv.toInt().toFloat()) fv.toInt().toString() else String.format("%.1f", fv)
                ctx.text(font, vs, (sx + sw + 4f).roundToInt().coerceAtMost((x + w - 4).roundToInt()), (y + 2f).roundToInt(), colTrinaryText.alpha(a).argb, false)
            }
            actual != null && actual.javaClass.simpleName.contains("Color", true) -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
                val col = try {
                    val argb = actual.javaClass.methods.firstOrNull { it.name == "argb" || it.name == "getArgb" }
                        ?.let { m -> if (m.parameterCount == 0) m.invoke(actual) as? Int else null }
                    if (argb != null) Color4b(argb) else colAccent
                } catch (_: Exception) { colAccent }
                ctx.drawRoundedRect(x + w - 22f, y + 3f, x + w - 6f, y + settingRowH - 3f, 3f, col.alpha(a))
            }
            else -> {
                ctx.text(font, v.name, (x + 8f).roundToInt(), ty.roundToInt(), colSecondaryText.alpha(a).argb, false)
            }
        }
    }
}
