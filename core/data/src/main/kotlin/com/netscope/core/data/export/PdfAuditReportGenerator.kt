package com.netscope.core.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.netscope.shared.export.ByteFormatter
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.TrafficAnalyticsSummary
import com.netscope.shared.model.TrafficSession
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfAuditReportGenerator {
  private val pageWidth = 595 // A4 @ 72dpi
  private val pageHeight = 842
  private val margin = 36f

  fun generate(
    session: TrafficSession,
    transactions: List<CapturedTransaction>,
    outputStream: OutputStream,
  ) {
    val summary = TrafficAnalyticsSummary.fromTransactions(transactions)
    val doc = PdfDocument()
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val titlePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(11, 19, 32)
        textSize = 20f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
      }
    val headingPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 42)
        textSize = 13f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
      }
    val bodyPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(51, 65, 85)
        textSize = 10f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
      }
    val monoPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 41, 59)
        textSize = 8.5f
        typeface = Typeface.MONOSPACE
      }
    val accentPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 145, 178)
        style = Paint.Style.FILL
      }
    val cardPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(241, 245, 249)
        style = Paint.Style.FILL
      }
    val dividerPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(203, 213, 225)
        strokeWidth = 1f
      }

    var pageNumber = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas: Canvas = page.canvas
    var y = margin + 12f

    // Header
    canvas.drawText("NetScope Network Security Audit Report", margin, y, titlePaint)
    y += 18f
    canvas.drawText(
      "Session: ${session.name}  |  Generated: ${dateFmt.format(Date())}  |  CA MITM: ${if (session.caEnabled) "Active" else "Off"}",
      margin,
      y,
      bodyPaint,
    )
    y += 12f
    canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint)
    y += 18f

    // KPI Cards
    val cardWidth = (pageWidth - margin * 2 - 24f) / 4f
    val kpis =
      listOf(
        "Requests" to summary.totalRequests.toString(),
        "Errors" to String.format(Locale.US, "%.1f%%", summary.errorRatePercent),
        "Avg Latency" to "${summary.avgLatencyMs} ms",
        "Traffic" to ByteFormatter.format(summary.totalBytesIn + summary.totalBytesOut),
      )
    kpis.forEachIndexed { idx, (label, value) ->
      val left = margin + idx * (cardWidth + 8f)
      canvas.drawRoundRect(RectF(left, y, left + cardWidth, y + 48f), 6f, 6f, cardPaint)
      canvas.drawText(label, left + 8f, y + 16f, bodyPaint)
      canvas.drawText(value, left + 8f, y + 36f, headingPaint)
    }
    y += 68f

    // Top Domains Chart
    canvas.drawText("Top Domains by Request Count", margin, y, headingPaint)
    y += 14f
    if (summary.topDomains.isEmpty()) {
      canvas.drawText("No domain telemetry recorded in this session.", margin, y, bodyPaint)
      y += 20f
    } else {
      val maxCount = summary.topDomains.maxOf { it.requestCount }.coerceAtLeast(1)
      val maxBarWidth = 240f
      summary.topDomains.forEach { stat ->
        canvas.drawText(stat.domain.take(32), margin, y + 11f, monoPaint)
        val barLeft = margin + 190f
        val barWidth = (stat.requestCount.toFloat() / maxCount) * maxBarWidth
        canvas.drawRoundRect(
          RectF(barLeft, y + 2f, barLeft + barWidth.coerceAtLeast(4f), y + 13f),
          3f,
          3f,
          accentPaint,
        )
        canvas.drawText(
          "${stat.requestCount} (${ByteFormatter.format(stat.totalBytes)})",
          barLeft + barWidth + 8f,
          y + 11f,
          bodyPaint,
        )
        y += 18f
      }
    }
    y += 12f

    // Status Distribution
    canvas.drawText("HTTP Status Distribution", margin, y, headingPaint)
    y += 14f
    if (summary.statusDistribution.isEmpty()) {
      canvas.drawText("No HTTP responses recorded.", margin, y, bodyPaint)
      y += 18f
    } else {
      val summaryLine =
        summary.statusDistribution.joinToString("   |   ") { "${it.label}: ${it.count}" }
      canvas.drawText(summaryLine, margin, y, bodyPaint)
      y += 22f
    }

    canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint)
    y += 18f
    canvas.drawText("Captured Transactions", margin, y, headingPaint)
    y += 14f
    canvas.drawText("METHOD   STATUS   LATENCY   SIZE       URL", margin, y, monoPaint)
    y += 6f
    canvas.drawLine(margin, y, pageWidth - margin, y, dividerPaint)
    y += 12f

    transactions.forEach { tx ->
      if (y > pageHeight - margin - 24f) {
        doc.finishPage(page)
        pageNumber++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page.canvas
        y = margin + 12f
        canvas.drawText("Captured Transactions (cont. page $pageNumber)", margin, y, headingPaint)
        y += 18f
      }
      val methodPad = tx.method.name.padEnd(7).take(7)
      val statusPad = (if (tx.statusCode > 0) tx.statusCode.toString() else "---").padEnd(7)
      val latPad = "${tx.timings.totalMs}ms".padEnd(8)
      val sizePad = ByteFormatter.format(tx.responseSizeBytes).padEnd(9)
      val urlTrim = tx.fullUrl.take(52)
      canvas.drawText("$methodPad  $statusPad  $latPad  $sizePad  $urlTrim", margin, y, monoPaint)
      y += 13f
    }

    doc.finishPage(page)
    doc.writeTo(outputStream)
    doc.close()
  }
}
