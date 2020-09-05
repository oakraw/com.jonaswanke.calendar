package com.jonaswanke.calendar

import android.content.Context
import android.gesture.GestureOverlayView
import android.graphics.Canvas
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewTreeObserver
import android.widget.CalendarView
import androidx.annotation.AttrRes
import androidx.core.content.withStyledAttributes
import androidx.core.view.doOnLayout
import com.jonaswanke.calendar.utils.*
import kotlinx.android.synthetic.main.view_calendar.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.properties.Delegates

/**
 * TODO: document your custom view class.
 */
class CalendarView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        @AttrRes defStyleAttr: Int = R.attr.calendarViewStyle
) : GestureOverlayView(context, attrs, defStyleAttr) {

    companion object {
        private const val GESTURE_STROKE_LENGTH = 10f
    }


    private var dayView: DayView? = null

    var onEventClickListener: ((Event) -> Unit)?
            by Delegates.observable<((Event) -> Unit)?>(null) { _, _, _ -> updateListeners() }
    var onEventLongClickListener: ((Event) -> Unit)?
            by Delegates.observable<((Event) -> Unit)?>(null) { _, _, _ -> updateListeners() }
    var onAddEventListener: ((AddEvent) -> Boolean)?
            by Delegates.observable<((AddEvent) -> Boolean)?>(null) { _, _, _ -> updateListeners() }

    var eventRequestCallback: (Day) -> Unit = {}

    var hourHeight: Float by Delegates.vetoable(0f) { _, old, new ->
        @Suppress("ComplexCondition")
        if ((hourHeightMin > 0 && new < hourHeightMin)
                || (hourHeightMax > 0 && new > hourHeightMax))
            return@vetoable false
        if (old == new)
            return@vetoable true

        hours.hourHeight = new
        views[visibleStart]?.hourHeight = new
        GlobalScope.launch(Dispatchers.Main) {
            for (view in views.values)
                if (view.range.start != visibleStart)
                    view.hourHeight = new
        }
        return@vetoable true
    }
    var hourHeightMin: Float by Delegates.observable(0f) { _, _, new ->
        if (new > 0 && hourHeight < new)
            hourHeight = new

        hours.hourHeightMin = new
        for (week in views.values)
            week.hourHeightMin = new
    }
    var hourHeightMax: Float by Delegates.observable(0f) { _, _, new ->
        if (new > 0 && hourHeight > new)
            hourHeight = new

        hours.hourHeightMax = new
        for (week in views.values)
            week.hourHeightMax = new
    }
    var scrollPosition: Int by Delegates.observable(0) { _, _, new ->
        hoursScroll.scrollY = new
        for (week in views.values)
            week.scrollTo(new)
    }

    var shouldHideHeader = true

    //extension views
    var androidCalendarView: CalendarView? = null

    private val events: MutableMap<Week, List<Event>> = mutableMapOf()
    private val views: MutableMap<Day, RangeView> = mutableMapOf()
    private val scaleDetector: ScaleGestureDetector

    var visibleStart: Day = Day()
        set(value) {
            if (field == value)
                return

            field = value
            addDayView(value)
        }


    init {
        View.inflate(context, R.layout.view_calendar, this)

        context.withStyledAttributes(attrs, R.styleable.CalendarView, defStyleAttr, R.style.Calendar_CalendarViewStyle) {
            hourHeightMin = getDimension(R.styleable.CalendarView_hourHeightMin, 0f)
            hourHeightMax = getDimension(R.styleable.CalendarView_hourHeightMax, 0f)
            hourHeight = getDimension(R.styleable.CalendarView_hourHeight, 0f)
            shouldHideHeader = getBoolean(R.styleable.CalendarView_hideHeader, false)
        }

        isGestureVisible = false
        gestureStrokeLengthThreshold = GESTURE_STROKE_LENGTH

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector?): Boolean {
                if (detector == null)
                    return false

                val foc = (detector.focusY + scrollPosition) / hourHeight
                hourHeight *= detector.currentSpanY / detector.previousSpanY
                scrollPosition = (foc * hourHeight - detector.focusY).toInt()

                return true
            }
        })

        addDayView(visibleStart)

//        // Request events
//        GlobalScope.launch(Dispatchers.Main) {
//            var currentWeek = week
//            var weeksLeft = ceil(range.toFloat() / WEEK_IN_DAYS).toInt()
//            while (weeksLeft > 0) {
//                eventRequestCallback(week)
//                currentWeek = currentWeek.nextWeek
//                weeksLeft--
//            }
//        }

        hoursScroll.onScrollChangeListener = { scrollPosition = it }
    }

    private fun addDayView(day: Day) {
        dayView = DayView(context, day = day)
        dayView?.let {dayView ->
            if (shouldHideHeader) dayView.hideHeader()

            dayView.setListeners(onEventClickListener, onEventLongClickListener, { _ ->
                for (otherView in views.values)
                    if (otherView != dayView)
                        otherView.removeAddEvent()
            }, onAddEventListener)
            dayView.onScrollChangeListener = { scrollPosition = it }
            dayView.hourHeightMin = hourHeightMin
            dayView.hourHeightMax = hourHeightMax
            dayView.hourHeight = hourHeight
            doOnLayout { _ -> dayView.scrollTo(scrollPosition) }

            scrollPosition = 0
            container.removeAllViews()
            container.addView(dayView)

            dayView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    eventRequestCallback.invoke(day)
                    dayView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })
        }
    }

    // View
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        scaleDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    // Custom
    private fun getEventsForRange(range: DayRange): List<Event> {
        var currentWeek = events.keys.minBy { it.start } ?: return emptyList()
        val viewEvents = mutableListOf<Event>()

        while (currentWeek.start < range.endExclusive.start) {
            val newEvents = events[currentWeek]
                    ?.filter { it.start < range.endExclusive.start && it.end > range.start.start }
            if (newEvents != null)
                viewEvents.addAll(newEvents)
            currentWeek = currentWeek.nextWeek
        }
        return viewEvents
    }

    fun setEvents(events: List<Event>) {
        dayView?.events = events
    }

    private fun onHeaderHeightUpdated() {
//        val firstPosition = when (pager.position) {
//            -1 -> views[visibleStart - range]
//            1 -> views[visibleStart + range]
//            else -> views[visibleStart]
//        }?.headerHeight ?: 0
//        val secondPosition = when (pager.position) {
//            0 -> views[visibleStart + range]
//            else -> views[visibleStart]
//        }?.headerHeight ?: 0
//        startIndicator?.minimumHeight = 0
    }

    private fun onRangeUpdated() {
        // Forces aligning to new length
//        visibleStart = visibleStart
//
//        startIndicator = when (range) {
//            RANGE_DAY -> RangeHeaderView(context, _range = visibleStart.range(1))
//            RANGE_WEEK -> WeekIndicatorView(context, _start = visibleStart)
//            else -> throw UnsupportedOperationException()
//        }
//        hoursCol.removeViewAt(0)
//        hoursCol.addView(startIndicator, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

//        pagerAdapter.reset(visibleStart)
    }

    private fun updateListeners() {
        for (view in views.values)
            view.setListeners(onEventClickListener, onEventLongClickListener, onAddEventListener)
    }


    // State
    override fun dispatchSaveInstanceState(container: SparseArray<Parcelable>?) {
        dispatchFreezeSelfOnly(container)
    }

    override fun onSaveInstanceState(): Parcelable {
        return SavedState(super.onSaveInstanceState()).also {
            it.visibleStart = visibleStart
            it.hourHeight = hourHeight
            it.hourHeightMin = hourHeightMin
            it.hourHeightMax = hourHeightMax
            it.scrollPosition = scrollPosition
        }
    }

    override fun dispatchRestoreInstanceState(container: SparseArray<Parcelable>?) {
        dispatchThawSelfOnly(container)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        // firstDayOfWeek not updated on restore automatically
        state.visibleStart?.also { visibleStart = Day(it.year, it.week, it.day) }
        state.hourHeightMin?.also { hourHeightMin = it }
        state.hourHeightMax?.also { hourHeightMax = it }
        state.hourHeight?.also { hourHeight = it }
        state.scrollPosition?.also { scrollPosition = it }
    }

    fun addExtension(calendarView: CalendarView) {
        androidCalendarView = calendarView
        androidCalendarView?.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val calendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
            }
            visibleStart = Day(calendar)
        }
    }

    internal class SavedState : View.BaseSavedState {
        companion object {
            @Suppress("unused")
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel?) = SavedState(source)
                override fun newArray(size: Int) = arrayOfNulls<SavedState>(size)
            }
        }

        var visibleStart: Day? = null

        var hourHeight: Float? = null
        var hourHeightMin: Float? = null
        var hourHeightMax: Float? = null
        var scrollPosition: Int? = null

        constructor(source: Parcel?) : super(source) {
            if (source == null)
                return

            fun readInt(): Int? {
                val value = source.readInt()
                return if (value == Int.MIN_VALUE) null else value
            }

            fun readFloat(): Float? {
                val value = source.readFloat()
                return if (value == Float.NaN) null else value
            }

            visibleStart = source.readString()?.toDay()
            hourHeight = readFloat()
            hourHeightMin = readFloat()
            hourHeightMax = readFloat()
            scrollPosition = readInt()
        }

        constructor(superState: Parcelable?) : super(superState)

        override fun writeToParcel(out: Parcel?, flags: Int) {
            super.writeToParcel(out, flags)
            out?.writeString(visibleStart?.toString())
            out?.writeFloat(hourHeight ?: Float.NaN)
            out?.writeFloat(hourHeightMin ?: Float.NaN)
            out?.writeFloat(hourHeightMax ?: Float.NaN)
            out?.writeInt(scrollPosition ?: Int.MIN_VALUE)
        }
    }
}
