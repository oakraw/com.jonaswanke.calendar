package com.jonaswanke.calendar.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.databinding.DataBindingUtil
import com.jonaswanke.calendar.BaseEvent
import com.jonaswanke.calendar.CalendarView
import com.jonaswanke.calendar.Event
import com.jonaswanke.calendar.example.databinding.ActivityMainBinding
import com.jonaswanke.calendar.utils.Day
import com.jonaswanke.calendar.utils.Week
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private var nextId: Long = 0
    private val random = Random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DataBindingUtil.setContentView<ActivityMainBinding>(this, R.layout.activity_main)
        binding.activity = this

        calendar.eventRequestCallback = { week ->
            populate(week)
        }
        calendar.onEventClickListener = {
            Toast.makeText(this, it.title + " clicked", Toast.LENGTH_SHORT).show()
        }
        calendar.onEventLongClickListener = {
            Toast.makeText(this, it.title + " long clicked", Toast.LENGTH_SHORT).show()
        }
        calendar.onAddEventListener = {
            val start = DateUtils.formatDateTime(this, it.start, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
            Toast.makeText(this, "Add event at $start", Toast.LENGTH_SHORT).show()
            true
        }
        calendar.addExtension(calendarAndroid)
//        calendar.visibleStart = Day(2020, 37, 1)
    }


    @Suppress("MagicNumber")
    private fun populate(week: Week, force: Boolean = false) {
        if (!force && calendar.cachedEvents.contains(week))
            return

        val events = mutableListOf<Event>()

        val start = week.toCalendar()
        events.add(BaseEvent("Fully Booked", null, 0XFFE4E4E4.toInt(), start.apply {
            set(Calendar.HOUR, 10)
            set(Calendar.MINUTE, 0)
        }.timeInMillis , start.apply {
            set(Calendar.HOUR, 11)
            set(Calendar.MINUTE, 0)
        }.timeInMillis))

        events.add(BaseEvent("Fully Booked", null, 0XFFE4E4E4.toInt(), start.apply {
            set(Calendar.HOUR, 10)
            set(Calendar.MINUTE, 30)
        }.timeInMillis , start.apply {
            set(Calendar.HOUR, 11)
            set(Calendar.MINUTE, 30)
        }.timeInMillis))



        calendar.setEventsForWeek(week, events)
    }

}
