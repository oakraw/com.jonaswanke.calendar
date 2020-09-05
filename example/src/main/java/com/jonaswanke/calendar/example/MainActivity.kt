package com.jonaswanke.calendar.example

import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.jonaswanke.calendar.BaseEvent
import com.jonaswanke.calendar.Event
import com.jonaswanke.calendar.example.databinding.ActivityMainBinding
import com.jonaswanke.calendar.utils.Day
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*

class MainActivity : AppCompatActivity() {
    private var nextId: Long = 0
    private val random = Random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DataBindingUtil.setContentView<ActivityMainBinding>(this, R.layout.activity_main)
        binding.activity = this

        calendar.eventRequestCallback = { day ->
            populate(day)
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

//        populate()
    }


    private fun populate(day: Day) {
        val events = mutableListOf<Event>()

        val start = day.toCalendar()
        events.add(BaseEvent("Fully Booked", null, 0XFFE4E4E4.toInt(), start.apply {
            set(Calendar.HOUR, start[Calendar.DAY_OF_MONTH])
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



        calendar.setEvents(events)
    }

}
