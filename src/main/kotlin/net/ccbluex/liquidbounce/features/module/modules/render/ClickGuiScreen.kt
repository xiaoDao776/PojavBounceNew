package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.awt.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class ClickGuiScreen : Screen(Component.literal("ClickGUI")) {

    private var cat = 0
    private var expanded: ClientModule? = null
    private var search = ""
    private var searchFocus = false
    private var listeningValue: Value<*>? = null // 当前正在等待按键绑定的 Value

    private var sOff = 0f; private var tOff = 0f
    private var sOff2 = 0f; private var tOff2 = 0f
    private var anim = 0f
    private var flash = 0f
    private var flashRow = -1

    // 调色板拖拽控制状态
    private var draggingColorValue: Value<*>? = null
    private var activePickerMode = 0 // 1: SV Box, 2: Hue, 3: Alpha

    private val cats = ModuleCategories.entries.toList()
    private val W = 450; private val H = 320
    private val panelW = 170

    private val accent = 0xFF4182E1.toInt()
    private val bg = 0xE80C0C10.toInt()
    private val panelBg = 0xE814141A.toInt()
    private val headerBg = 0xF0000000.toInt()
    private val textGray = 0xFFA0A0AA.toInt()

    override fun isPauseScreen() = false
    override fun shouldCloseOnEsc() = true

    private fun fillRoundedRect(ctx: GuiGraphics, x1: Float, y1: Float, x2: Float, y2: Float, radius: Float, color: Int) {
        val r = radius.coerceAtMost((x2 - x1) / 2f).coerceAtMost((y2 - y1) / 2f)
        ctx.fill((x1 + r).toInt(), y1.toInt(), (x2 - r).toInt(), y2.toInt(), color)
        ctx.fill(x1.toInt(), (y1 + r).toInt(), (x1 + r).toInt(), (y2 - r).toInt(), color)
        ctx.fill((x2 - r).toInt(), (y1 + r).toInt(), x2.toInt(), (y2 - r).toInt(), color)

        val corners = arrayOf(
            floatArrayOf(x1 + r, y1 + r, 180f, 270f),
            floatArrayOf(x2 - r, y1 + r, 270f, 360f),
            floatArrayOf(x2 - r, y2 - r, 0f, 90f),
            floatArrayOf(x1 + r, y2 - r, 90f, 180f)
        )

        for (c in corners) {
            val cx = c[0]; val cy = c[1]
            val startAng = c[2]; val endAng = c[3]
            var a = startAng
            while (a < endAng) {
                val rad1 = Math.toRadians(a.toDouble())
                val rad2 = Math.toRadians((a + 10).coerceAtMost(endAng).toDouble())
                
                val px1 = cx + (cos(rad1) * r).toFloat()
                val py1 = cy + (sin(rad1) * r).toFloat()
                val px2 = cx + (cos(rad2) * r).toFloat()
                val py2 = cy + (sin(rad2) * r).toFloat()

                val minX = cx.coerceAtMost(px1).coerceAtMost(px2).toInt()
                val maxX = cx.coerceAtLeast(px1).coerceAtLeast(px2).toInt()
                val minY = cy.coerceAtMost(py1).coerceAtMost(py2).toInt()
                val maxY = cy.coerceAtLeast(py1).coerceAtLeast(py2).toInt()

                ctx.fill(minX, minY, max(minX + 1, maxX), max(minY + 1, maxY), color)
                a += 10f
            }
        }
    }

    private fun trimText(font: Font, text: String, maxW: Int): String {
        if (font.width(text) <= maxW) return text
        var str = text
        while (str.isNotEmpty() && font.width("$str...") > maxW) {
            str = str.substring(0, str.length - 1)
        }
        return "$str..."
    }

    private fun getActualValue(v: Value<*>): Any? {
        var obj: Any? = v.get() ?: return null
        var depth = 0
        while (obj is Value<*> && depth < 5) {
            obj = obj.get()
            depth++
        }
        if (obj is Collection<*>) {
            val first = obj.firstOrNull() ?: return "EMPTY"
            if (first is Value<*>) return getActualValue(first)
            return first
        } else if (obj != null && obj.javaClass.isArray) {
            val arr = obj as Array<*>
            val first = arr.firstOrNull() ?: return "EMPTY"
            if (first is Value<*>) return getActualValue(first)
            return first
        }
        return obj
    }

    private fun isBindValue(v: Value<*>): Boolean {
        val name = v.name.lowercase()
        if (name.contains("key") || name.contains("bind")) return true
        val actual = getActualValue(v) ?: return false
        val clsName = actual.javaClass.simpleName.lowercase()
        return clsName.contains("key") || clsName.contains("bind")
    }

    private fun formatDisplayValue(v: Value<*>): String {
        if (v == listeningValue) return "[LISTENING...]"

        val actual = getActualValue(v) ?: return "NONE"

        try {
            val cls = actual.javaClass
            val keyField = cls.declaredFields.find { 
                it.name.equals("boundKey", true) || it.name.equals("key", true) || it.name.equals("name", true) 
            }
            if (keyField != null) {
                keyField.isAccessible = true
                val innerKey = keyField.get(actual)
                if (innerKey != null) {
                    val keyStr = innerKey.toString()
                        .replace("key.keyboard.", "", ignoreCase = true)
                        .replace("key.", "", ignoreCase = true)
                        .uppercase()
                    if (keyStr.isNotEmpty()) return keyStr
                }
            }
        } catch (_: Exception) {}

        var str = actual.toString()

        if (str.contains("Value(") || str.contains("name=")) {
            val match = Regex("""name=([^,\s\)]+)""").find(str)
            if (match != null) {
                return match.groupValues[1].uppercase()
            }
        }

        str = str.replace("key.keyboard.", "", ignoreCase = true)
            .replace("key.", "", ignoreCase = true)
            .replace("InputBind", "", ignoreCase = true)

        if (str.startsWith("(") && str.endsWith(")")) {
            str = str.substring(1, str.length - 1)
        }

        return if (str.isBlank()) "NONE" else str.take(18).uppercase()
    }

    private fun isSliderValue(v: Value<*>): Boolean {
        val obj = getActualValue(v) ?: return false
        return obj is Number || obj is ClosedRange<*> || v is RangedValue<*>
    }

    private fun isColorValue(v: Value<*>): Boolean {
        val obj = getActualValue(v) ?: return false
        if (obj is Color) return true
        val className = obj.javaClass.simpleName
        return className.contains("Color", true) || v.name.contains("Color", true)
    }

    private fun extractColor(v: Value<*>): Color {
        val valObj = getActualValue(v) ?: return Color.WHITE
        if (valObj is Color) return valObj
        if (valObj is Number) return Color(valObj.toInt(), true)
        
        try {
            val cls = valObj.javaClass
            val rgbMethod = cls.methods.find { it.name == "getRGB" || it.name == "rgb" }
            if (rgbMethod != null) {
                val rgb = (rgbMethod.invoke(valObj) as Number).toInt()
                return Color(rgb, true)
            }
        } catch (_: Exception) {}

        return Color.WHITE
    }

    private fun updateColorValue(v: Value<*>, color: Color) {
        val actual = getActualValue(v)
        try {
            if (actual is Color) {
                @Suppress("UNCHECKED_CAST")
                (v as Value<Color>).set(color)
            } else {
                @Suppress("UNCHECKED_CAST")
                (v as Value<Int>).set(color.rgb)
            }
        } catch (_: Exception) {}
    }

    private fun toggleNextValue(v: Value<*>) {
        val cls = v.javaClass

        try {
            val nextMethod = cls.methods.find { 
                (it.name == "next" || it.name == "toggle" || it.name == "setNext") && it.parameterCount == 0 
            }
            if (nextMethod != null) {
                nextMethod.invoke(v)
                return
            }
        } catch (_: Exception) {}

        val actual = getActualValue(v)

        if (actual is Enum<*>) {
            val constants = actual.javaClass.enumConstants
            if (constants != null && constants.isNotEmpty()) {
                val nextIdx = (actual.ordinal + 1) % constants.size
                val nextVal: Any = constants[nextIdx]
                try {
                    @Suppress("UNCHECKED_CAST")
                    (v as Value<Any>).set(nextVal)
                } catch (_: Exception) {}
                return
            }
        }

        try {
            val choicesField = cls.declaredFields.find { 
                it.name.equals("values", true) || it.name.equals("choices", true) || 
                it.name.equals("modes", true) || it.name.equals("range", true) 
            }
            if (choicesField != null) {
                choicesField.isAccessible = true
                val choices = choicesField.get(v)
                if (choices is Array<*>) {
                    val idx = choices.indexOf(v.get())
                    val nextIdx = if (idx >= 0) (idx + 1) % choices.size else 0
                    val nextVal = choices[nextIdx]
                    if (nextVal != null) {
                        @Suppress("UNCHECKED_CAST")
                        (v as Value<Any>).set(nextVal)
                    }
                    return
                } else if (choices is List<*>) {
                    val idx = choices.indexOf(v.get())
                    val nextIdx = if (idx >= 0) (idx + 1) % choices.size else 0
                    val nextVal = choices[nextIdx]
                    if (nextVal != null) {
                        @Suppress("UNCHECKED_CAST")
                        (v as Value<Any>).set(nextVal)
                    }
                    return
                }
            }
        } catch (_: Exception) {}

        if (actual is Boolean) {
            try {
                @Suppress("UNCHECKED_CAST")
                (v as Value<Boolean>).set(!actual)
            } catch (_: Exception) {}
        }
    }

    override fun render(ctx: GuiGraphics, mx: Int, my: Int, dt: Float) {
        anim += (1f - anim) * 0.25f
        val a = anim.coerceIn(0f, 1f)
        if (a < 0.01f) return

        if (flash > 0f) flash -= dt / 3f
        else flash = 0f

        val x = (minecraft!!.window.guiScaledWidth - W) / 2f
        val y = (minecraft!!.window.guiScaledHeight - H) / 2f
        val f = minecraft!!.font
        val tabW = (W - 24) / cats.size

        val R = 8f
        fillRoundedRect(ctx, x, y, x + W, y + H, R, bg)
        
        ctx.fill(x.toInt() + R.toInt(), y.toInt(), (x + W - R).toInt(), (y + 24).toInt(), headerBg)
        ctx.drawString(f, "§lClickGUI", x.toInt() + 10, y.toInt() + 5, accent)

        val searchY = y + 28
        ctx.fill(x.toInt() + 8, searchY.toInt(), (x + W - 8).toInt(), (searchY + 15).toInt(), 0x28000000.toInt())
        val disp = if (search.isEmpty()) "§7Search modules..." else "§f$search"
        ctx.drawString(f, trimText(f, disp, W - 30), x.toInt() + 12, searchY.toInt() + 2, -1)
        if (searchFocus) {
            val cx = x.toInt() + 12 + f.width(search)
            if (cx < x + W - 12) {
                ctx.fill(cx, searchY.toInt() + 2, cx + 1, searchY.toInt() + 13, 0xFFFFFFFF.toInt())
            }
        }

        val tabY = searchY + 20
        ctx.fill(x.toInt() + 4, tabY.toInt(), (x + W - 4).toInt(), (tabY + 20).toInt(), 0x18000000.toInt())
        for (i in cats.indices) {
            val tx = x + 8 + i * tabW
            val sel = i == cat
            if (sel) {
                ctx.fill(tx.toInt(), tabY.toInt(), (tx + tabW - 2).toInt(), (tabY + 20).toInt(), accent)
                ctx.fill(tx.toInt(), (tabY + 18).toInt(), (tx + tabW - 2).toInt(), (tabY + 20).toInt(), 0xFF2A5DB0.toInt())
            } else if (mx in tx.toInt()..(tx + tabW - 2).toInt() && my in tabY.toInt()..(tabY + 20).toInt()) {
                ctx.fill(tx.toInt(), tabY.toInt(), (tx + tabW - 2).toInt(), (tabY + 20).toInt(), 0x20FFFFFF.toInt())
            }
            val tagStr = trimText(f, cats[i].tag, tabW - 4)
            val cw = f.width(tagStr)
            ctx.drawString(f, tagStr, tx.toInt() + ((tabW - 2) - cw) / 2, tabY.toInt() + 4, if (sel) -1 else textGray)
        }

        val divY = tabY + 22
        ctx.fill(x.toInt() + 8, divY.toInt(), (x + W - 8).toInt(), (divY + 1).toInt(), 0x20FFFFFF.toInt())

        val mods = getMods()
        val listRight = x + W - panelW - 8
        val listY = divY + 6
        val listH = H - (listY - y) - 8
        val rowH = 18

        tOff = max(0f, tOff.coerceAtMost(max(0f, mods.size * rowH - listH)))
        sOff += (tOff - sOff) * 0.3f * a

        for (i in mods.indices) {
            val mod = mods[i]
            val my2 = listY + i * rowH - sOff
            if (my2 < listY - rowH || my2 > listY + listH) continue
            val mi = my2.toInt()
            val hov = mx in (x.toInt() + 8)..listRight.toInt() && my in mi..(mi + rowH)

            if (hov) ctx.fill(x.toInt() + 8, mi, listRight.toInt(), mi + rowH, 0x14FFFFFF.toInt())
            if (flash > 0f && flashRow == i) {
                val fa = (flash * 80).toInt()
                ctx.fill(x.toInt() + 8, mi, listRight.toInt(), mi + rowH, (fa shl 24) or 0x00FFFFFF)
            }

            val isExpandedMod = expanded == mod
            val nameText = trimText(f, (if (isExpandedMod) "§n" else "") + mod.name, (listRight - x - 45).toInt())
            ctx.drawString(f, nameText, x.toInt() + 14, mi + 3, if (mod.enabled) accent else textGray)

            val switchW = 24
            val switchH = 12
            val btnX = listRight.toInt() - switchW - 4
            val btnY = mi + (rowH - switchH) / 2

            if (mod.enabled) {
                ctx.fill(btnX, btnY, btnX + switchW, btnY + switchH, accent)
                ctx.fill(btnX + switchW - 10, btnY + 2, btnX + switchW - 2, btnY + switchH - 2, 0xFFFFFFFF.toInt())
            } else {
                ctx.fill(btnX, btnY, btnX + switchW, btnY + switchH, 0x30FFFFFF.toInt())
                ctx.fill(btnX + 2, btnY + 2, btnX + 10, btnY + switchH - 2, 0xAA808080.toInt())
            }
        }

        val curExp = expanded
        if (curExp != null) {
            val px = x + W - panelW - 2
            val py = listY
            val maxTextW = panelW - 28

            fillRoundedRect(ctx, px, py, x + W - 2, y + H - 2, 4f, panelBg)

            val setList = curExp.collectValuesRecursively()
            
            val paddingTop = 8f
            val setY = py + paddingTop
            val setH = H - (py - y) - 12f

            var totalContentH = 0f
            for (v in setList) {
                totalContentH += when {
                    isColorValue(v) -> 75f // 高级调色板面板的高度
                    isSliderValue(v) -> 20f
                    else -> 16f
                }
            }

            tOff2 = max(0f, tOff2.coerceAtMost(max(0f, totalContentH - setH)))
            sOff2 += (tOff2 - sOff2) * 0.3f

            ctx.enableScissor(px.toInt(), (py + 4).toInt(), (x + W - 2).toInt(), (y + H - 6).toInt())

            var curY = setY - sOff2
            for (v in setList) {
                val isColor = isColorValue(v)
                val isSlider = isSliderValue(v)
                val itemH = when {
                    isColor -> 75f
                    isSlider -> 20f
                    else -> 16f
                }

                if (curY >= py - itemH && curY <= py + setH + itemH) {
                    val mi2 = curY.toInt()
                    try {
                        val actualVal = getActualValue(v)
                        when {
                            isColor -> {
                                val c = extractColor(v)
                                val text = trimText(f, "${v.name}:", maxTextW)
                                ctx.drawString(f, text, px.toInt() + 8, mi2, -1)

                                val hexStr = "#%02X%02X%02X%02X".format(c.alpha, c.red, c.green, c.blue)
                                ctx.drawString(f, "§7$hexStr", px.toInt() + 8, mi2 + 12, -1)

                                // 类似 Ambience 的调色板区域
                                val boxX = px.toInt() + 8
                                val boxY = mi2 + 24
                                val boxW = 90
                                val boxH = 45

                                // 渲染 SV (Saturation-Value) 基础渐变网格
                                val hsv = Color.RGBtoHSB(c.red, c.green, c.blue, null)
                                for (gx in 0 until boxW step 3) {
                                    for (gy in 0 until boxH step 3) {
                                        val sat = gx.toFloat() / boxW
                                        val valStep = 1f - (gy.toFloat() / boxH)
                                        val rgb = Color.HSBtoRGB(hsv[0], sat, valStep)
                                        ctx.fill(boxX + gx, boxY + gy, boxX + gx + 3, boxY + gy + 3, rgb or 0xFF000000.toInt())
                                    }
                                }
                                val circleX = boxX + (hsv[1] * boxW).toInt()
                                val circleY = boxY + ((1f - hsv[2]) * boxH).toInt()
                                ctx.fill(circleX - 2, circleY - 2, circleX + 2, circleY + 2, 0xFFFFFFFF.toInt())

                                // 色相条 Hue Bar
                                val hueX = boxX + boxW + 8
                                val barW = 8
                                for (gh in 0 until boxH step 2) {
                                    val hueStep = gh.toFloat() / boxH
                                    val rgb = Color.HSBtoRGB(hueStep, 1f, 1f)
                                    ctx.fill(hueX, boxY + gh, hueX + barW, boxY + gh + 2, rgb or 0xFF000000.toInt())
                                }
                                val hueY = boxY + (hsv[0] * boxH).toInt()
                                ctx.fill(hueX - 1, hueY - 1, hueX + barW + 1, hueY + 1, 0xFFFFFFFF.toInt())

                                // Alpha Bar
                                val alphaX = hueX + barW + 6
                                for (ga in 0 until boxH step 2) {
                                    val aRatio = 1f - (ga.toFloat() / boxH)
                                    val aInt = (aRatio * 255).toInt()
                                    ctx.fill(alphaX, boxY + ga, alphaX + barW, boxY + ga + 2, (aInt shl 24) or (c.rgb and 0x00FFFFFF))
                                }
                                val alphaY = boxY + ((1f - (c.alpha / 255f)) * boxH).toInt()
                                ctx.fill(alphaX - 1, alphaY - 1, alphaX + barW + 1, alphaY + 1, 0xFFFFFFFF.toInt())

                                // 颜色预览块
                                val swatchX = alphaX + barW + 6
                                ctx.fill(swatchX, boxY, swatchX + 12, boxY + boxH, c.rgb)
                            }
                            actualVal is Boolean -> {
                                val text = trimText(f, "${v.name}: ${if (actualVal) "§aON" else "§cOFF"}", maxTextW)
                                ctx.drawString(f, text, px.toInt() + 8, mi2 + 2, -1)
                            }
                            isBindValue(v) -> {
                                val dispStr = formatDisplayValue(v)
                                val text = trimText(f, "${v.name}: §e$dispStr", maxTextW)
                                ctx.drawString(f, text, px.toInt() + 8, mi2 + 2, -1)
                            }
                            isSlider -> {
                                var fv = 0f; var mn = 0f; var mxr = 20f
                                if (actualVal is ClosedRange<*>) {
                                    fv = (actualVal.endInclusive as? Number)?.toFloat() ?: 20f
                                    mn = 1f; mxr = 30f
                                } else if (actualVal is Number) {
                                    fv = actualVal.toFloat()
                                    if (v is RangedValue<*>) {
                                        mn = (v.range.start as? Number)?.toFloat() ?: 0f
                                        mxr = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
                                    }
                                }

                                val bw = panelW - 16; val bh = 4
                                val bx = px.toInt() + 8; val by = mi2 + 13
                                ctx.fill(bx, by, bx + bw, by + bh, 0x30000000.toInt())
                                val r = ((fv - mn) / max(0.001f, mxr - mn)).coerceIn(0f, 1f)
                                ctx.fill(bx, by, (bx + bw * r).toInt(), by + bh, accent)

                                val dispVal = if (actualVal is ClosedRange<*>) "${actualVal.start} - ${actualVal.endInclusive}" else "%.1f".format(fv)
                                val text = trimText(f, "${v.name}: $dispVal", maxTextW)
                                ctx.drawString(f, text, px.toInt() + 8, mi2, -1)
                            }
                            else -> {
                                val dispStr = formatDisplayValue(v)
                                val text = trimText(f, "${v.name}: §b$dispStr", maxTextW)
                                ctx.drawString(f, text, px.toInt() + 8, mi2 + 2, -1)
                            }
                        }
                    } catch (_: Exception) {}
                }
                curY += itemH
            }

            ctx.disableScissor()
        }
    }

    override fun renderBackground(ctx: GuiGraphics, mx: Int, my: Int, dt: Float) {}

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val btn = click.button()
        val mx = click.x.toInt(); val my = click.y.toInt()
        val x = (minecraft!!.window.guiScaledWidth - W) / 2
        val y = (minecraft!!.window.guiScaledHeight - H) / 2
        val tabW = (W - 24) / cats.size

        if (mx in (x + 8)..(x + W - 8) && my in (y + 28)..(y + 43)) {
            searchFocus = true
            return true
        }
        searchFocus = false

        val tabY = y + 48
        if (btn == 0 && my in tabY..(tabY + 20)) {
            for (i in cats.indices) {
                val tx = x + 8 + i * tabW
                if (mx in tx..(tx + tabW)) {
                    cat = i
                    sOff = 0f; tOff = 0f
                    expanded = null
                    listeningValue = null
                    return true
                }
            }
        }

        val divY = tabY + 22
        val listY = divY + 6
        val listH = H - (listY - y) - 8
        val listRight = x + W - panelW - 8
        val rowH = 18

        if (mx in (x + 8)..listRight && my in listY..(listY + listH)) {
            val mods = getMods()
            val clickIdx = ((my - listY + sOff) / rowH).toInt()

            if (clickIdx in mods.indices) {
                val mod = mods[clickIdx]
                if (btn == 0) {
                    mod.enabled = !mod.enabled
                    flash = 1f; flashRow = clickIdx
                } else if (btn == 1) {
                    expanded = if (expanded == mod) null else mod
                    sOff2 = 0f; tOff2 = 0f
                    flash = 1f; flashRow = clickIdx
                    listeningValue = null
                }
                return true
            }
        }

        val curExp = expanded
        if (curExp != null && btn == 0) {
            val px = x + W - panelW - 2
            val py = listY
            val paddingTop = 8f
            val setY = py + paddingTop
            val setH = H - (py - y) - 12f

            if (mx in px..(px + panelW) && my in py.toInt()..(py + setH).toInt()) {
                val setList = curExp.collectValuesRecursively()
                var curY = setY - sOff2

                for (v in setList) {
                    val isColor = isColorValue(v)
                    val isSlider = isSliderValue(v)
                    val isBind = isBindValue(v)
                    val itemH = when {
                        isColor -> 75f
                        isSlider -> 20f
                        else -> 16f
                    }

                    if (my >= curY && my < curY + itemH) {
                        try {
                            val actualVal = getActualValue(v)
                            when {
                                isColor -> {
                                    val c = extractColor(v)
                                    val boxX = px.toInt() + 8
                                    val boxY = curY.toInt() + 24
                                    val boxW = 90
                                    val boxH = 45
                                    val barW = 8

                                    val hueX = boxX + boxW + 8
                                    val alphaX = hueX + barW + 6

                                    val hsv = Color.RGBtoHSB(c.red, c.green, c.blue, null)

                                    if (mx in boxX..(boxX + boxW) && my in boxY..(boxY + boxH)) {
                                        val sat = ((mx - boxX).toFloat() / boxW).coerceIn(0f, 1f)
                                        val br = (1f - ((my - boxY).toFloat() / boxH)).coerceIn(0f, 1f)
                                        val newRgb = Color.HSBtoRGB(hsv[0], sat, br)
                                        val newColor = Color((newRgb and 0x00FFFFFF) or (c.alpha shl 24), true)
                                        updateColorValue(v, newColor)
                                    } else if (mx in hueX..(hueX + barW) && my in boxY..(boxY + boxH)) {
                                        val newHue = ((my - boxY).toFloat() / boxH).coerceIn(0f, 1f)
                                        val newRgb = Color.HSBtoRGB(newHue, hsv[1], hsv[2])
                                        val newColor = Color((newRgb and 0x00FFFFFF) or (c.alpha shl 24), true)
                                        updateColorValue(v, newColor)
                                    } else if (mx in alphaX..(alphaX + barW) && my in boxY..(boxY + boxH)) {
                                        val newAlpha = ((1f - ((my - boxY).toFloat() / boxH)) * 255).toInt().coerceIn(0, 255)
                                        val newColor = Color(c.red, c.green, c.blue, newAlpha)
                                        updateColorValue(v, newColor)
                                    }
                                }
                                isBind -> {
                                    // 开启等待按键输入状态
                                    listeningValue = if (listeningValue == v) null else v
                                }
                                actualVal is Boolean -> {
                                    @Suppress("UNCHECKED_CAST")
                                    (v as Value<Boolean>).set(!actualVal)
                                }
                                isSlider -> {
                                    var mn = 0f; var mxr = 20f
                                    if (actualVal is ClosedRange<*>) {
                                        mn = 1f; mxr = 30f
                                    } else if (v is RangedValue<*>) {
                                        mn = (v.range.start as? Number)?.toFloat() ?: 0f
                                        mxr = (v.range.endInclusive as? Number)?.toFloat() ?: 100f
                                    }

                                    val bw = panelW - 16; val bx = px + 8
                                    val nr = ((mx - bx).toFloat() / bw).coerceIn(0f, 1f)
                                    val nv = mn + nr * (mxr - mn)

                                    if (actualVal is IntRange) {
                                        val center = nv.toInt()
                                        @Suppress("UNCHECKED_CAST")
                                        (v as Value<IntRange>).set((center - 1).coerceAtLeast(1)..center)
                                    } else if (actualVal is Float) {
                                        @Suppress("UNCHECKED_CAST")
                                        (v as Value<Float>).set(nv)
                                    } else if (actualVal is Int) {
                                        @Suppress("UNCHECKED_CAST")
                                        (v as Value<Int>).set(nv.toInt())
                                    }
                                }
                                else -> {
                                    toggleNextValue(v)
                                }
                            }
                        } catch (_: Exception) {}
                        return true
                    }
                    curY += itemH
                }
            }
        }

        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(mx: Double, my: Double, h: Double, v: Double): Boolean {
        val x = (minecraft!!.window.guiScaledWidth - W) / 2
        val panelX = x + W - panelW - 2
        if (expanded != null && mx >= panelX) {
            tOff2 = (tOff2 - v.toFloat() * 18f).coerceAtLeast(0f)
        } else {
            tOff = (tOff - v.toFloat() * 18f).coerceAtLeast(0f)
        }
        return true
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        // 如果处于按键绑定监听阶段，捕获任何按键并写入 Bind 属性
        val lv = listeningValue
        if (lv != null) {
            val key = input.key
            val targetKeyName = if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE) "NONE" else GLFW.glfwGetKeyName(key, 0)?.uppercase() ?: "KEY_$key"

            try {
                val actual = getActualValue(lv)
                if (actual != null) {
                    val cls = actual.javaClass
                    val fields = cls.declaredFields
                    val keyField = fields.find { it.name.contains("key", true) || it.name.contains("bound", true) }
                    
                    if (keyField != null) {
                        keyField.isAccessible = true
                        keyField.set(actual, key)
                    } else if (actual is Int) {
                        @Suppress("UNCHECKED_CAST")
                        (lv as Value<Int>).set(key)
                    } else if (actual is String) {
                        @Suppress("UNCHECKED_CAST")
                        (lv as Value<String>).set(targetKeyName)
                    }
                }
            } catch (_: Exception) {}

            listeningValue = null
            return true
        }

        if (input.key == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }

        if (searchFocus) {
            when (input.key) {
                GLFW.GLFW_KEY_BACKSPACE -> {
                    if (search.isNotEmpty()) search = search.dropLast(1)
                    return true
                }
                GLFW.GLFW_KEY_SPACE -> {
                    search += " "
                    return true
                }
                else -> {
                    val n = GLFW.glfwGetKeyName(input.key, 0)
                    if (n != null && n.length == 1) {
                        search += n
                        return true
                    }
                }
            }
        }
        return super.keyPressed(input)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (searchFocus) {
            try {
                val obj: Any = characterEvent
                val cls = obj.javaClass
                var cp: Int? = null

                for (m in cls.methods) {
                    if (m.parameterCount == 0 && (m.name.equals("codepoint", true) || m.name.equals("codePoint", true) || m.name.equals("character", true))) {
                        val res = m.invoke(obj)
                        if (res is Int) cp = res
                        else if (res is Char) cp = res.code
                        if (cp != null) break
                    }
                }

                if (cp == null) {
                    for (f in cls.declaredFields) {
                        if (f.type == Int::class.javaPrimitiveType || f.type == Char::class.javaPrimitiveType) {
                            f.isAccessible = true
                            val v = f.get(obj)
                            if (v is Int) cp = v
                            else if (v is Char) cp = v.code
                            if (cp != null) break
                        }
                    }
                }

                if (cp != null && cp > 31) {
                    search += cp.toChar().toString()
                    return true
                }
            } catch (_: Exception) {}
        }
        return super.charTyped(characterEvent)
    }

    override fun onClose() {
        minecraft?.setScreen(null)
        anim = 0f
    }

    private fun getMods(): List<ClientModule> {
        val catObj = cats.getOrElse(cat) { ModuleCategories.COMBAT }
        return ModuleManager.getModules()
            .filter { it.category == catObj && it.name != "ClickGUI" }
            .filter { search.isEmpty() || it.name.contains(search, ignoreCase = true) }
    }
}
