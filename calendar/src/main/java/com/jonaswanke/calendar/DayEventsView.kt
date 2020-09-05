package com.jonaswanke.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.TimeUtils
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.children
import androidx.core.view.get
import com.jonaswanke.calendar.RangeView.Companion.showAsAllDay
import com.jonaswanke.calendar.utils.*
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates

/**
 * TODO: document your custom view class.
 */
@Suppress("LargeClass", "TooManyFunctions")
class DayEventsView @JvmOverloads constructor(
        context: Context,
        private val attrs: AttributeSet? = null,
        @AttrRes private val defStyleAttr: Int = R.attr.dayEventsViewStyle,
        @StyleRes private val defStyleRes: Int = R.style.Calendar_DayEventsViewStyle,
        _day: Day? = null
) : ViewGroup(ContextThemeWrapper(context, defStyleRes), attrs, defStyleAttr) {
    companion object {
        private const val EVENT_POSITIONING_DEBOUNCE = 500L
    }

    private var _onEventClickListener: ((Event) -> Unit)? = null
    var onEventClickListener: ((Event) -> Unit)?
        get() = _onEventClickListener
        set(value) {
            if (_onEventClickListener == value)
                return

            _onEventClickListener = value
            updateListeners()
        }
    private var _onEventLongClickListener: ((Event) -> Unit)? = null
    var onEventLongClickListener: ((Event) -> Unit)?
        get() = _onEventLongClickListener
        set(value) {
            if (_onEventLongClickListener == value)
                return

            _onEventLongClickListener = value
            updateListeners()
        }
    internal var onAddEventViewListener: ((AddEvent) -> Unit)? = null
    private var _onAddEventListener: ((AddEvent) -> Boolean)? = null
    var onAddEventListener: ((AddEvent) -> Boolean)?
        get() = _onAddEventListener
        set(value) {
            if (value == null)
                removeAddEvent()
            if (_onAddEventListener == value)
                return

            _onAddEventListener = value
            updateListeners()
        }

    var day: Day = _day ?: Day()
        private set
    private var events: List<Event> = emptyList()
    private val eventData: MutableMap<Event, EventData> = mutableMapOf()
    private var addEventView: EventView? = null

    private var startTime: Int = 0
    private var endTime: Int = 0
    private var timeCycle: Int = 0
    private var timeCircleRadius: Int = 0
    private var timeLineSize: Int = 0
    private lateinit var timePaint: Paint

    private var _hourHeight: Float = 0f
    var hourHeight: Float
        get() = _hourHeight
        set(value) {
            val v = value.coerceIn(if (hourHeightMin > 0) hourHeightMin else null,
                    if (hourHeightMax > 0) hourHeightMax else null)
            if (_hourHeight == v)
                return

            _hourHeight = v
            invalidate()
            requestPositionEventsAndLayout()
        }
    var hourHeightMin: Float by Delegates.observable(0f) { _, _, new ->
        if (new > 0 && hourHeight < new)
            hourHeight = new
    }
    var hourHeightMax: Float by Delegates.observable(0f) { _, _, new ->
        if (new > 0 && hourHeight > new)
            hourHeight = new
    }
    private var eventPositionRequired: Boolean = false
    private var eventPositionJob: Job? = null

    private var dividerPadding: Int = 0

    private var eventSpacing: Float = 0f
    private var eventStackOverlap: Float = 0f

    internal var divider by Delegates.observable<Drawable?>(null) { _, _, new ->
        dividerHeight = new?.intrinsicHeight ?: 0
    }
        private set
    private var dividerHeight: Int = 0
    private val timeSlot = mutableListOf<Long>()

    private val cal: Calendar

    init {
        setWillNotDraw(false)

        context.withStyledAttributes(attrs, R.styleable.DayEventsView, defStyleAttr, defStyleRes) {
            _hourHeight = getDimension(R.styleable.DayEventsView_hourHeight, 0f)
            hourHeightMin = getDimension(R.styleable.DayEventsView_hourHeightMin, 0f)
            hourHeightMax = getDimension(R.styleable.DayEventsView_hourHeightMax, 0f)
            startTime = getInteger(R.styleable.DayEventsView_startTime, 60)
            endTime = getInteger(R.styleable.DayEventsView_endTime, 1440)
            timeCycle = getInteger(R.styleable.DayEventsView_timeCycle, 60)

            dividerPadding = getDimensionPixelOffset(R.styleable.DayEventsView_dividerPadding, 0)

            eventSpacing = getDimension(R.styleable.DayEventsView_eventSpacing, 0f)
            eventStackOverlap = getDimension(R.styleable.DayEventsView_eventStackOverlap, 0f)
        }

        onUpdateDay(day)
        cal = day.start.toCalendar()
        GlobalScope.launch(Dispatchers.Main) {
            divider = ContextCompat.getDrawable(context, android.R.drawable.divider_horizontal_bright)
            invalidate()
        }

        setOnTouchListener { _, motionEvent ->
            if (onAddEventListener == null)
                return@setOnTouchListener false

            if (motionEvent.action == MotionEvent.ACTION_UP) {
                fun calendarInMillis(slotIndex: Int) = timeSlot[slotIndex]
                fun timeInMillis(slotIndex: Int): Long {
                    val c = Calendar.getInstance().apply { timeInMillis = timeSlot[slotIndex] }
                    return c.get(Calendar.HOUR_OF_DAY) * DateUtils.HOUR_IN_MILLIS + c.get(Calendar.MINUTE) * DateUtils.MINUTE_IN_MILLIS
                }

                val slotIndex = (motionEvent.y / hourHeight).toInt()

                if (slotIndex >= timeSlot.lastIndex) return@setOnTouchListener false

                val event = AddEvent(calendarInMillis(slotIndex), calendarInMillis(slotIndex + 1))
                eventData[event] = EventData(timeInMillis(slotIndex).toInt(),
                        timeInMillis(slotIndex + 1).toInt())

                val view = addEventView
                if (view == null) {
                    addView(EventView(context,
                            defStyleAttr = R.attr.eventViewAddStyle,
                            defStyleRes = R.style.Calendar_EventViewStyle_Add,
                            _event = event).apply {
                        setOnClickListener {
                            if (onAddEventListener?.invoke(event) == true)
                                removeAddEvent()
                        }
                    })
                } else {
                    view.event = event
                    requestLayout()
                }
                onAddEventViewListener?.invoke(event)
            }
            return@setOnTouchListener motionEvent.action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)
        }
    }


    // View
    override fun addView(child: View?, index: Int, params: LayoutParams?) {
        if (child !is EventView)
            throw IllegalArgumentException("Only EventViews may be children of DayEventsView")
        if (child.event is AddEvent)
            if (addEventView != null && addEventView != child)
                throw  IllegalStateException("DayEventsView may only contain one add-EventView")
            else {
                addEventView = child
                onAddEventViewListener?.invoke(child.event as AddEvent)
            }
        super.addView(child, index, params)
    }

    override fun getPaddingTop(): Int {
        return super.getPaddingTop() + 10.px
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        calculateTimeSlot()
        val height = paddingTop + paddingBottom + max(suggestedMinimumHeight, (_hourHeight * timeSlot.size).toInt())
        setMeasuredDimension(View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                height)
    }

    private fun calculateTimeSlot() {
        timeSlot.clear()

        val startCalendar = day.toCalendar().apply {
            set(Calendar.HOUR_OF_DAY, startTime / 60)
            set(Calendar.MINUTE, startTime % 60)
        }

        val endCalendar = day.toCalendar().apply {
            set(Calendar.HOUR_OF_DAY, endTime / 60)
            set(Calendar.MINUTE, endTime % 60)
        }

//        timeSlot.add(startCalendar.timeInMillis)
        while (startCalendar < endCalendar) {
            timeSlot.add(startCalendar.timeInMillis)
            startCalendar.add(Calendar.MINUTE, timeCycle)
        }
        timeSlot.add(startCalendar.timeInMillis)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = paddingLeft
        val top = paddingTop
        val right = r - l - paddingRight
        val bottom = b - t - paddingBottom
        val height = bottom - top
        val width = right - left

        fun getPosForTime(time: Long): Int {
            return when {
                time < timeSlot.first() -> 0
//                time >= timeSlot.last() -> height
                else -> height * timeSlot.indexOf(time) / timeSlot.size
            }
        }

        for (view in children) {
            val eventView = view as EventView
            val event = eventView.event ?: continue

            val data = eventData[event] ?: continue
            val eventTop = min(top + getPosForTime(event.start), bottom - eventView.minHeight).toFloat()
            // Fix if event ends on next day
            val eventBottom = if (event.end >= day.nextDay.start)
                bottom.toFloat()
            else
                max(top + getPosForTime(event.end) - eventSpacing, eventTop + eventView.minHeight)
            val subGroupWidth = width / data.parallel
            val subGroupLeft = left + subGroupWidth * data.index + eventSpacing

            eventView.layout((subGroupLeft + data.subIndex * eventSpacing).toInt(), eventTop.toInt() + eventSpacing.toInt(),
                    (subGroupLeft + subGroupWidth - eventSpacing).toInt(), eventBottom.toInt())
        }
    }

    override fun draw(canvas: Canvas?) {
        super.draw(canvas)
        if (canvas == null)
            return

        val left = (dividerPadding - timeCircleRadius).coerceAtLeast(0)
        val top = paddingTop
        val right = width - paddingRight
        val bottom = height - paddingBottom

        if (day.isToday) {
            val time = Calendar.getInstance().timeInMillis - timeSlot.first()
            val posY = top + (time * ((bottom.toFloat() - _hourHeight) - top) / (timeSlot.last() - timeSlot.first()))
            canvas.drawCircle(left.toFloat(), posY, timeCircleRadius.toFloat(), timePaint)
            canvas.drawRect(left.toFloat(), posY - timeLineSize / 2,
                    right.toFloat(), posY + timeLineSize / 2, timePaint)
        }
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (canvas == null)
            return

        val left = dividerPadding
        val top = paddingTop
        val right = width - dividerPadding

        timeSlot.forEachIndexed() { index, _ ->
            divider?.setBounds(left, (top + _hourHeight * index).toInt(),
                    right, (top + _hourHeight * index + dividerHeight).toInt())
            divider?.draw(canvas)
        }
    }

    fun setTimeLine(startTime: Int? = null, endTime: Int? = null, timeCycle: Int? = null) {
        startTime?.let { this.startTime = it }
        endTime?.let { this.endTime = it }
        timeCycle?.let { this.timeCycle = it }
        requestLayout()
    }


    // Custom
    fun setDay(day: Day, events: List<Event> = emptyList()) {
        this.day = day
        onUpdateDay(day)

        removeAddEvent()
        setEvents(events)
    }

    fun setEvents(events: List<Event>) {
        checkEvents(events)

        regenerateBaseEventData(events)
        this.events = events.sortedWith(compareBy({ eventData[it]?.start },
                { -(eventData[it]?.end ?: Int.MIN_VALUE) }))

        GlobalScope.launch(Dispatchers.Main) {
            @Suppress("NAME_SHADOWING")
            val events = this@DayEventsView.events
            positionEvents()

            if (addEventView != null)
                removeView(addEventView)

            val existing = childCount
            for (i in 0 until events.size) {
                val event = events[i]

                if (existing > i)
                    (this@DayEventsView[i] as EventView).event = event
                else
                    addView(EventView(this@DayEventsView.context).also {
                        it.event = event
                    })
            }
            if (events.size < existing)
                removeViews(events.size, existing - events.size)
            if (addEventView != null)
                addView(addEventView)

            updateListeners()
            requestLayout()
        }
    }

    fun removeAddEvent() {
        addEventView?.also {
            removeView(it)
            addEventView = null
        }
    }

    fun setListeners(
            onEventClickListener: ((Event) -> Unit)?,
            onEventLongClickListener: ((Event) -> Unit)?,
            onAddEventListener: ((AddEvent) -> Boolean)?
    ) {
        _onEventClickListener = onEventClickListener
        _onEventLongClickListener = onEventLongClickListener
        _onAddEventListener = onAddEventListener
        updateListeners()
    }

    internal fun setListeners(
            onEventClickListener: ((Event) -> Unit)?,
            onEventLongClickListener: ((Event) -> Unit)?,
            onAddEventViewListener: ((AddEvent) -> Unit)?,
            onAddEventListener: ((AddEvent) -> Boolean)?
    ) {
        _onEventClickListener = onEventClickListener
        _onEventLongClickListener = onEventLongClickListener
        this.onAddEventViewListener = onAddEventViewListener
        _onAddEventListener = onAddEventListener
        updateListeners()
    }


    // Helpers
    private fun regenerateBaseEventData(events: List<Event>) {
        val view = if (childCount > 0) (this[0] as EventView) else EventView(context)
        val minLength = (view.minHeight / hourHeight * DateUtils.HOUR_IN_MILLIS).toLong()

        eventData.clear()
        for (event in events + addEventView?.event) {
            if (event == null)
                continue

            val start = (event.start - timeSlot.first()).coerceIn(0, DateUtils.DAY_IN_MILLIS - minLength).toInt()
            eventData[event] = EventData(start,
                    (event.end - timeSlot.first()).coerceIn(start + minLength, DateUtils.DAY_IN_MILLIS).toInt())
        }
    }

    @Suppress("ComplexMethod", "NestedBlockDepth")
    private fun positionEvents() {
        val view = if (childCount > 0) (this[0] as EventView) else EventView(context)
        val minLength = (view.minHeight / hourHeight * DateUtils.HOUR_IN_MILLIS).toLong()
        val spacing = eventSpacing / hourHeight * DateUtils.HOUR_IN_MILLIS
        val stackOverlap = eventStackOverlap / hourHeight * DateUtils.HOUR_IN_MILLIS

        regenerateBaseEventData(events)

        fun endOfNoSpacing(event: Event) = (event.end - timeSlot.first())
                .coerceIn((eventData[event]?.start
                        ?: 0) + minLength - spacing.toLong(), DateUtils.DAY_IN_MILLIS)

        var currentGroup = mutableListOf<Event>()
        fun endGroup() {
            when (currentGroup.size) {
                0 -> return
                1 -> return
                else -> {
                    val columns = mutableListOf<MutableList<Event>>()
                    for (event in currentGroup) {
                        val data = eventData[event] ?: continue

                        var minIndex = Int.MIN_VALUE
                        var minSubIndex = Int.MAX_VALUE
                        var minTop = Int.MAX_VALUE
                        var minIsStacking = false
                        for (index in columns.indices) {
                            val column = columns[index]
                            for (subIndex in column.indices.reversed()) {
                                val other = column[subIndex]
                                val otherData = eventData[other] ?: continue

                                // No space in current subgroup
                                if (otherData.start + stackOverlap > data.start && endOfNoSpacing(other) >= data.start)
                                    break

                                // Stacking
                                val (top, isStacking) = if (otherData.start + stackOverlap <= data.start
                                        && endOfNoSpacing(other) >= data.start)
                                    (otherData.start + stackOverlap).toInt() to true
                                // Below other
                                else if (otherData.end <= data.start)
                                    otherData.end to false
                                // Too close
                                else
                                    continue

                                // Wider and further at the top
                                if (minSubIndex >= subIndex) {
                                    minIndex = index
                                    minSubIndex = subIndex
                                    minTop = top
                                    minIsStacking = isStacking

                                    if (otherData.end >= data.start)
                                        break
                                }
                            }
                        }

                        // If no column fits
                        if (minTop == Int.MAX_VALUE) {
                            eventData[event]?.index = columns.size
                            columns.add(mutableListOf(event))
                            continue
                        }

                        val subIndex = if (minIsStacking) minSubIndex + 1 else minSubIndex
                        eventData[event]?.also {
                            it.index = minIndex
                            it.subIndex = subIndex
                        }

                        val column = columns[minIndex]
                        if (column.size > subIndex)
                            column[subIndex] = event
                        else
                            column.add(event)
                    }
                    for (e in currentGroup)
                        eventData[e]?.parallel = columns.size
                }
            }
        }

        var currentEnd = 0
        loop@ for (event in events) {
            val data = eventData[event] ?: continue
            when {
                event is AddEvent -> continue@loop
                data.start <= currentEnd -> {
                    currentGroup.add(event)
                    currentEnd = max(currentEnd, data.end)
                }
                else -> {
                    endGroup()
                    currentGroup = mutableListOf(event)
                    currentEnd = data.end
                }
            }
        }
        endGroup()
    }

    private fun requestPositionEventsAndLayout() {
        requestLayout()

        if (eventPositionJob == null)
            eventPositionJob = GlobalScope.launch(Dispatchers.Main) {
                eventPositionRequired = false
                delay(EVENT_POSITIONING_DEBOUNCE)
                positionEvents()
                requestLayout()
                eventPositionJob = null
                if (eventPositionRequired)
                    requestPositionEventsAndLayout()
            }
        else
            eventPositionRequired = true
    }

    @Suppress("ThrowsCount")
    private fun checkEvents(events: List<Event>) {
        if (events.any { showAsAllDay(it) })
            throw IllegalArgumentException("all-day events cannot be shown inside DayEventsView")
        if (events.any { it is AddEvent })
            throw IllegalArgumentException("add events currently cannot be set from the outside")
        if (events.any { it.end < day.start || it.start >= day.end })
            throw IllegalArgumentException("event starts must all be inside the set day")
    }

    private fun updateListeners() {
        for (view in children) {
            val eventView = view as EventView
            val event = eventView.event
            if (event == null) {
                eventView.setOnClickListener(null)
                eventView.setOnLongClickListener(null)
                continue
            }

            onEventClickListener?.let { listener ->
                eventView.setOnClickListener {
                    listener(event)
                }
            } ?: eventView.setOnClickListener(null)
            onEventLongClickListener?.let { listener ->
                eventView.setOnLongClickListener {
                    listener(event)
                    true
                }
            } ?: eventView.setOnLongClickListener(null)
        }
    }

    private fun onUpdateDay(day: Day) {
        context.withStyledAttributes(attrs, R.styleable.DayEventsView, defStyleAttr, defStyleRes) {
            background = if (day.isToday)
                getDrawable(R.styleable.DayEventsView_dateCurrentBackground)
            else
                null

            if (day.isToday && !this@DayEventsView::timePaint.isInitialized) {
                timeCircleRadius = getDimensionPixelSize(R.styleable.DayEventsView_timeCircleRadius, 0)
                timeLineSize = getDimensionPixelSize(R.styleable.DayEventsView_timeLineSize, 0)
                val timeColor = getColor(R.styleable.DayEventsView_timeColor, Color.BLACK)
                timePaint = Paint().apply {
                    color = timeColor
                }
            }
        }
    }

    private data class EventData(
            val start: Int,
            val end: Int,
            var parallel: Int = 1,
            var index: Int = 0,
            var subIndex: Int = 0
    )
}
