package com.netscope.feature.injector.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.netscope.feature.injector.data.OverlayDataExtractor
import com.netscope.shared.model.CapturedTransaction

internal object OverlayPalette {
  val bgPanel = Color.rgb(30, 32, 34)       // #1E2022
  val bgHeader = Color.rgb(24, 26, 31)      // #181A1F
  val bgTabBar = Color.rgb(36, 38, 41)      // #242629
  val bgTabActive = Color.rgb(44, 47, 51)   // #2C2F33
  val bgRowAlt = Color.rgb(27, 35, 51)      // #1B2333
  val bgInput = Color.rgb(20, 23, 28)       // #14171C
  val bgButton = Color.rgb(45, 50, 58)      // #2D323A
  val border = Color.rgb(58, 61, 66)        // #3A3D42
  val divider = Color.rgb(43, 46, 51)       // #2B2E33
  val accentBlue = Color.rgb(94, 151, 246)  // #5E97F6
  val accentCyan = Color.rgb(0, 229, 255)   // #00E5FF
  val textPrimary = Color.rgb(226, 232, 240)
  val textMuted = Color.rgb(154, 160, 166)
  val greenOk = Color.rgb(52, 211, 153)
  val amberWarn = Color.rgb(251, 191, 36)
  val redErr = Color.rgb(248, 113, 113)
}

internal object OverlayUi {
  fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()

  fun copyToClipboard(ctx: Context, label: String, text: String, toastMsg: String = "Скопировано в буфер") {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
  }

  fun readClipboardText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
  }

  fun styleTabButton(ctx: Context, btn: Button, selected: Boolean) {
    btn.setTextColor(if (selected) Color.WHITE else OverlayPalette.textMuted)
    btn.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    if (selected) {
      val bgLayer = GradientDrawable().apply { setColor(OverlayPalette.bgTabActive) }
      val underline = GradientDrawable().apply { setColor(OverlayPalette.accentBlue) }
      val ld = LayerDrawable(arrayOf(bgLayer, underline))
      val h = dp(ctx, 2)
      ld.setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
      ld.setLayerHeight(1, h)
      btn.background = ld
    } else {
      btn.setBackgroundColor(Color.TRANSPARENT)
    }
  }

  fun sectionHeader(ctx: Context, title: String): TextView =
    TextView(ctx).apply {
      text = title
      setTextColor(OverlayPalette.accentCyan)
      textSize = 11.5f
      setTypeface(null, Typeface.BOLD)
      setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
    }

  @SuppressLint("UseSwitchCompatOrMaterialCode")
  fun switchRow(
    ctx: Context,
    label: String,
    initial: Boolean,
    onChanged: (Boolean) -> Unit,
  ): LinearLayout =
    LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
      addView(
        TextView(ctx).apply {
          text = label
          setTextColor(OverlayPalette.textPrimary)
          textSize = 11.5f
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
      )
      addView(
        Switch(ctx).apply {
          isChecked = initial
          setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
      )
    }

  fun darkInput(ctx: Context, hintText: String, initial: String = ""): EditText =
    EditText(ctx).apply {
      setText(initial)
      hint = hintText
      setTextColor(Color.WHITE)
      setHintTextColor(OverlayPalette.textMuted)
      textSize = 11.5f
      typeface = Typeface.MONOSPACE
      setSingleLine(true)
      setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
      background =
        GradientDrawable().apply {
          setColor(OverlayPalette.bgInput)
          cornerRadius = dp(ctx, 6).toFloat()
          setStroke(dp(ctx, 1), OverlayPalette.border)
        }
    }

  fun chipButton(
    ctx: Context,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
  ): Button =
    Button(ctx).apply {
      text = label
      textSize = 10.5f
      setTextColor(if (accent) Color.rgb(6, 12, 22) else OverlayPalette.textPrimary)
      if (accent) setTypeface(null, Typeface.BOLD)
      setAllCaps(false)
      setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
      minimumHeight = dp(ctx, 30)
      minHeight = dp(ctx, 30)
      minimumWidth = 0
      minWidth = 0
      background =
        GradientDrawable().apply {
          setColor(if (accent) OverlayPalette.accentCyan else OverlayPalette.bgButton)
          cornerRadius = dp(ctx, 5).toFloat()
        }
      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(ctx, 4) }
      setOnClickListener { onClick() }
    }

  fun iconBarButton(ctx: Context, symbol: String, color: Int, desc: String, onClick: () -> Unit): Button =
    Button(ctx).apply {
      text = symbol
      contentDescription = desc
      setTextColor(color)
      textSize = 13f
      setAllCaps(false)
      setBackgroundColor(Color.TRANSPARENT)
      minimumWidth = dp(ctx, 34)
      minWidth = dp(ctx, 34)
      minimumHeight = dp(ctx, 34)
      minHeight = dp(ctx, 34)
      setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0)
      setOnClickListener { onClick() }
    }

  fun buildNetworkTableHeader(ctx: Context): LinearLayout {
    val row =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(OverlayPalette.bgTabBar)
      }
    val cols =
      listOf(
        "Имя" to 2.2f,
        "Метод" to 0.85f,
        "Статус" to 0.85f,
        "Тип" to 0.7f,
        "Размер" to 0.9f,
        "Время" to 0.9f,
      )
    cols.forEachIndexed { idx, (title, weight) ->
      row.addView(
        tableCell(
          ctx = ctx,
          text = title,
          weight = weight,
          color = OverlayPalette.textMuted,
          bold = false,
          hasRightBorder = idx < cols.lastIndex,
        )
      )
    }
    return wrapWithBottomBorder(ctx, row, OverlayPalette.border)
  }

  fun buildNetworkTableRow(
    ctx: Context,
    tx: CapturedTransaction,
    index: Int,
    onClick: () -> Unit,
  ): LinearLayout {
    val display = OverlayDataExtractor.formatNetworkRow(tx)
    val bgColor = if (index % 2 == 1) OverlayPalette.bgRowAlt else OverlayPalette.bgPanel
    val statusColor =
      when {
        tx.statusCode in 200..299 -> OverlayPalette.textPrimary
        tx.statusCode in 300..399 -> OverlayPalette.accentCyan
        tx.statusCode in 400..499 -> OverlayPalette.amberWarn
        tx.statusCode >= 500 -> OverlayPalette.redErr
        else -> OverlayPalette.textMuted
      }

    val row =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(bgColor)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
      }
    row.addView(tableCell(ctx, display.name, 2.2f, OverlayPalette.textPrimary, bold = false, hasRightBorder = true))
    row.addView(tableCell(ctx, display.method, 0.85f, OverlayPalette.textMuted, bold = false, hasRightBorder = true))
    row.addView(tableCell(ctx, display.status, 0.85f, statusColor, bold = false, hasRightBorder = true))
    row.addView(tableCell(ctx, display.type, 0.7f, OverlayPalette.textMuted, bold = false, hasRightBorder = true))
    row.addView(tableCell(ctx, display.size, 0.9f, OverlayPalette.textMuted, bold = false, hasRightBorder = true))
    row.addView(tableCell(ctx, display.time, 0.9f, OverlayPalette.textMuted, bold = false, hasRightBorder = false))
    return wrapWithBottomBorder(ctx, row, OverlayPalette.divider)
  }

  private fun tableCell(
    ctx: Context,
    text: String,
    weight: Float,
    color: Int,
    bold: Boolean,
    hasRightBorder: Boolean,
  ): LinearLayout {
    val cellBox =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
      }
    val tv =
      TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        textSize = 10.5f
        typeface = Typeface.MONOSPACE
        if (bold) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(ctx, 5), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    cellBox.addView(tv)
    if (hasRightBorder) {
      cellBox.addView(
        View(ctx).apply {
          setBackgroundColor(OverlayPalette.divider)
          layoutParams = LinearLayout.LayoutParams(dp(ctx, 1).coerceAtLeast(1), LinearLayout.LayoutParams.MATCH_PARENT)
        }
      )
    }
    return cellBox
  }

  fun wrapWithBottomBorder(ctx: Context, content: LinearLayout, borderColor: Int): LinearLayout =
    LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
      addView(
        View(ctx).apply {
          setBackgroundColor(borderColor)
          layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1).coerceAtLeast(1))
        }
      )
    }
}
