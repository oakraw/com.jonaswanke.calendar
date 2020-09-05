package com.jonaswanke.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.withStyledAttributes
import com.jonaswanke.calendar.utils.px
import java.util.*
import kotlin.properties.Delegates


/**
 * TODO: document your custom view class.
 */
class HoursView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        @AttrRes defStyleAttr: Int = R.attr.hoursViewStyle
) : View(context, attrs, defStyleAttr) {
    companion object {
        private const val DIGIT_MAX = 9
    }


    private var _hourHeight: Float = 0f
    var hourHeight: Float
        get() = _hourHeight
        set(value) {
            val v = value.coerceIn(if (hourHeightMin > 0) hourHeightMin else null,
                    if (hourHeightMax > 0) hourHeightMax else null)
            if (_hourHeight == v)
                return

            _hourHeight = v
            requestLayout()
        }
    var hourHeightMin: Float by Delegates.observable(0f) { _, _, new ->
        if (new > 0 && hourHeight < new)
            hourHeight = new
    }
    var hourHeightMax: Float by Delegates.observable(0f) { _, _, new ->
        if (new > 0 && hourHeight > new)
            hourHeight = new
    }

    private var startTime: Int = 0
    private var endTime: Int = 0
    private var timeCycle: Int = 0
    private var hourSize: Int = 0
    private var hourColor: Int = 0
    private var hourFont: Int = 0
    private lateinit var hourPaint: TextPaint

    private val timeSlot = mutableListOf<String>()

    init {
        context.withStyledAttributes(attrs, R.styleable.HoursView, defStyleAttr, R.style.Calendar_HoursViewStyle) {
            _hourHeight = getDimension(R.styleable.HoursView_hourHeight, 0f)
            hourHeightMin = getDimension(R.styleable.HoursView_hourHeightMin, 0f)
            hourHeightMax = getDimension(R.styleable.HoursView_hourHeightMax, 0f)
            startTime = getInteger(R.styleable.HoursView_startTime, 60)
            endTime = getInteger(R.styleable.HoursView_endTime, 1440)
            timeCycle = getInteger(R.styleable.HoursView_timeCycle, 60)
            hourSize = getDimensionPixelSize(R.styleable.HoursView_hourSize, 0)
            hourColor = getColor(R.styleable.HoursView_hourColor, Color.BLACK)
            hourFont = getResourceId(R.styleable.HoursView_android_fontFamily, 0)
            hourPaint = TextPaint().apply {
                color = hourColor
                isAntiAlias = true
                textSize = hourSize.toFloat()
                if (hourFont > 0) typeface = ResourcesCompat.getFont(context, hourFont)
            }
        }
    }

    override fun getPaddingTop(): Int {
        return super.getPaddingTop() + 10.px
    }

    // View
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        calculateTimeSlot()

        val height = paddingTop + paddingBottom + Math.max(suggestedMinimumHeight, (_hourHeight * timeSlot.size).toInt())
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                height)
    }

    fun setTimeLine(startTime: Int? = null, endTime: Int? = null, timeCycle: Int? = null) {
        startTime?.let { this.startTime = it }
        endTime?.let { this.endTime = it }
        timeCycle?.let { this.timeCycle = it }
        requestLayout()
    }

    private fun calculateTimeSlot() {
        timeSlot.clear()

        val startCalendar = Calendar.getInstance().apply {
            timeInMillis = 0
            set(Calendar.HOUR_OF_DAY, startTime / 60)
            set(Calendar.MINUTE, startTime % 60)
        }

        val endCalendar = Calendar.getInstance().apply {
            timeInMillis = 0
            set(Calendar.HOUR_OF_DAY, endTime / 60)
            set(Calendar.MINUTE, endTime % 60)
        }

//        timeSlot.add("")
        while (startCalendar < endCalendar) {
            timeSlot.add("${String.format("%02d", startCalendar[Calendar.HOUR_OF_DAY])}:${String.format("%02d", startCalendar[Calendar.MINUTE])}")
            startCalendar.add(Calendar.MINUTE, timeCycle)
        }
        timeSlot.add("${String.format("%02d", startCalendar[Calendar.HOUR_OF_DAY])}:${String.format("%02d", startCalendar[Calendar.MINUTE])}")
    }

    private val hourBounds = Rect()
    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (canvas == null)
            return

        val left = paddingLeft
        val top = paddingTop
        val right = width - paddingRight

        fun getStartForCentered(width: Float): Float {
            return left.toFloat() + (right - left - width) / 2
        }

        fun getBottomForCentered(center: Float, height: Int): Float {
            return center + height / 2
        }

        timeSlot.forEachIndexed() { index, hourText ->
            hourPaint.getTextBounds(hourText, 0, hourText.length, hourBounds)
            canvas.drawText(hourText,
                    getStartForCentered(hourBounds.width().toFloat()),
                    getBottomForCentered(top + _hourHeight * index + 1, hourBounds.height()),
                    hourPaint)
        }
    }

}
