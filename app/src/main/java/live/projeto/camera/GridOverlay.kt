package live.projeto.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

/**
 * A grade de terços e, com o celular deitado, o recorte vertical 9:16 do meio da imagem (as
 * laterais que ficam fora do vídeo vertical aparecem mais escuras). Igual ao app do iPhone.
 * Fica só na tela: não vai para a imagem enviada ao OBS.
 */
class GridOverlay(context: Context) : View(context) {
    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private val thirds = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val shade = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private val vertical = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 214, 0)
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val labelBack = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 214, 0)
        textSize = dp(12f)
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        if (w > h) {
            val verticalWidth = h * 9f / 16f
            val side = (w - verticalWidth) / 2f
            canvas.drawRect(0f, 0f, side, h, shade)
            canvas.drawRect(w - side, 0f, w, h, shade)
            canvas.drawRect(side, 0f, side + verticalWidth, h, vertical)

            val text = "Vertical 9:16"
            val textWidth = labelText.measureText(text)
            val pad = dp(6f)
            val top = dp(70f) // abaixo da faixa de estado do topo
            val left = (w - textWidth) / 2f
            canvas.drawRoundRect(
                left - pad, top, left + textWidth + pad, top + labelText.textSize + pad * 1.2f,
                dp(4f), dp(4f), labelBack
            )
            canvas.drawText(text, left, top + labelText.textSize + pad * 0.2f, labelText)
        }

        for (step in 1..2) {
            val x = w * step / 3f
            val y = h * step / 3f
            canvas.drawLine(x, 0f, x, h, thirds)
            canvas.drawLine(0f, y, w, y, thirds)
        }
    }
}
