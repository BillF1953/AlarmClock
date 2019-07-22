package com.better.alarm.presenter

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.*
import android.view.ContextMenu.ContextMenuInfo
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.fragment.app.Fragment
import com.better.alarm.R
import com.better.alarm.configuration.AlarmApplication.container
import com.better.alarm.configuration.Layout
import com.better.alarm.configuration.Prefs
import com.better.alarm.lollipop
import com.better.alarm.model.AlarmValue
import com.melnykov.fab.FloatingActionButton
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.disposables.Disposables
import java.util.*

/**
 * Shows a list of alarms. To react on user interaction, requires a strategy. An
 * activity hosting this fragment should provide a proper strategy for single
 * and multi-pane modes.
 *
 * @author Yuriy
 */
class AlarmsListFragment : Fragment() {
    private val alarms = container().alarms()
    private val store = container().store()
    private val uiStore: UiStore by lazy { AlarmsListActivity.uiStore(activity as AlarmsListActivity, alarms) }
    private val prefs: Prefs = container().prefs()
    private val logger = container().logger()

    private val mAdapter: AlarmListAdapter by lazy { AlarmListAdapter(R.layout.list_row_classic, R.string.alarm_list_title, ArrayList()) }
    private val inflater: LayoutInflater by lazy { requireActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater }

    private var alarmsSub: Disposable = Disposables.disposed()
    private var backSub: Disposable = Disposables.disposed()
    private var timePickerDialogDisposable = Disposables.disposed()

    /** changed by [Prefs.listRowLayout] in [onResume]*/
    private var listRowLayoutId = R.layout.list_row_classic
    /** changed by [Prefs.listRowLayout] in [onResume]*/
    private var listRowLayout = prefs.layout()

    inner class AlarmListAdapter(alarmTime: Int, label: Int, private val values: List<AlarmValue>) : ArrayAdapter<AlarmValue>(requireContext(), alarmTime, label, values) {

        private fun recycleView(convertView: View?, parent: ViewGroup, id: Int): RowHolder {
            val tag = convertView?.tag

            return when {
                tag is RowHolder && tag.layout == listRowLayout -> RowHolder(convertView, id, listRowLayout)
                else -> {
                    val rowView = inflater.inflate(listRowLayoutId, parent, false)
                    RowHolder(rowView, id, listRowLayout).apply {
                        digitalClock.setLive(false)
                    }
                }
            }
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            // get the alarm which we have to display
            val alarm = values[position]

            val row = recycleView(convertView, parent, alarm.id)

            row.onOff().isChecked = alarm.isEnabled

            lollipop {
                row.digitalClock().transitionName = "clock" + alarm.id
                row.container().transitionName = "onOff" + alarm.id
                row.detailsButton().transitionName = "detailsButton" + alarm.id
            }

            //Delete add, skip animation
            if (row.idHasChanged()) {
                row.onOff().jumpDrawablesToCurrentState()
            }

            row.container()
                    //onOff
                    .setOnClickListener {
                        val enable = !alarm.isEnabled
                        logger.d("onClick: " + if (enable) "enable" else "disable")
                        alarms.enable(alarm, enable)
                    }

            row.digitalClock().setOnClickListener {
                timePickerDialogDisposable = TimePickerDialogFragment.showTimePicker(fragmentManager)
                        .subscribe { picked ->
                            if (picked.isPresent()) {
                                alarms.getAlarm(alarm.id)?.also { alarm ->
                                    alarm.edit()
                                            .withIsEnabled(true)
                                            .withHour(picked.get().hour)
                                            .withMinutes(picked.get().minute)
                                            .commit()
                                }
                            }
                        }
            }

            // set the alarm text
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, alarm.hour)
            c.set(Calendar.MINUTE, alarm.minutes)
            row.digitalClock().updateTime(c)

            val removeEmptyView = listRowLayout == Layout.CLASSIC || listRowLayout == Layout.COMPACT
            // Set the repeat text or leave it blank if it does not repeat.
            val daysOfWeekStr = alarm.daysOfWeek.toString(context, false)

            row.daysOfWeek().text = daysOfWeekStr

            row.daysOfWeek().visibility = when {
                daysOfWeekStr.isNotEmpty() -> View.VISIBLE
                removeEmptyView -> View.GONE
                else -> View.INVISIBLE
            }

            // Set the repeat text or leave it blank if it does not repeat.
            row.label().text = alarm.label

            row.label().visibility = when {
                alarm.label.isNotBlank() -> View.VISIBLE
                removeEmptyView -> View.GONE
                else -> View.INVISIBLE
            }

            // row.labelsContainer.visibility = when {
            //     row.label().visibility == View.GONE && row.daysOfWeek().visibility == View.GONE -> GONE
            //     else -> View.VISIBLE
            // }

            return row.rowView()
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val info = item.menuInfo as AdapterContextMenuInfo
        val alarm = mAdapter.getItem(info.position)
        return when {
            alarm == null -> false
            item.itemId == R.id.delete_alarm -> {
                // Confirm that the alarm will be deleted.
                AlertDialog.Builder(activity).setTitle(getString(R.string.delete_alarm))
                        .setMessage(getString(R.string.delete_alarm_confirm))
                        .setPositiveButton(android.R.string.ok) { d, w -> alarms.delete(alarm) }.setNegativeButton(android.R.string.cancel, null).show()
                true
            }

            item.itemId == R.id.enable_alarm -> {
                alarms.enable(alarm, !alarm.isEnabled)
                true
            }

            item.itemId == R.id.edit_alarm -> {
                uiStore.edit(alarm.id)
                true
            }

            else -> {
                super.onContextItemSelected(item)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        logger.d("onCreateView $this")

        val view = inflater.inflate(R.layout.list_fragment, container, false)

        val listView = view.findViewById(R.id.list_fragment_list) as ListView

        listView.adapter = mAdapter

        listView.isVerticalScrollBarEnabled = false
        listView.setOnCreateContextMenuListener(this)
        listView.choiceMode = AbsListView.CHOICE_MODE_SINGLE

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, listRow, position, _ ->
            mAdapter.getItem(position)?.run {
                uiStore.edit(id, listRow.tag as RowHolder)
            }
        }

        registerForContextMenu(listView)

        setHasOptionsMenu(true)

        val fab: View = view.findViewById(R.id.fab)
        fab.setOnClickListener { uiStore.createNewAlarm() }

        lollipop {
            (fab as FloatingActionButton).attachToListView(listView)
        }

        alarmsSub =
                prefs.listRowLayout()
                        .switchMap { uiStore.transitioningToNewAlarmDetails() }
                        .switchMap { transitioning -> if (transitioning) Observable.never() else store.alarms() }
                        .subscribe { alarms ->
                            val sorted = alarms
                                    .sortedWith(Comparators.MinuteComparator())
                                    .sortedWith(Comparators.HourComparator())
                                    .sortedWith(Comparators.RepeatComparator())
                            mAdapter.clear()
                            mAdapter.addAll(sorted)
                        }

        return view
    }

    override fun onResume() {
        super.onResume()
        backSub = uiStore.onBackPressed().subscribe { activity?.finish() }
        listRowLayout = prefs.layout()
        listRowLayoutId = when (listRowLayout) {
            Layout.COMPACT -> R.layout.list_row_compact
            Layout.CLASSIC -> R.layout.list_row_classic
            else -> R.layout.list_row_bold
        }
    }

    override fun onPause() {
        super.onPause()
        backSub.dispose()
        //dismiss the time picker if it was showing. Otherwise we will have to uiStore the state and it is not nice for the user
        timePickerDialogDisposable.dispose()
    }

    override fun onDestroy() {
        super.onDestroy()
        alarmsSub.dispose()
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenuInfo) {
        // Inflate the menu from xml.
        requireActivity().menuInflater.inflate(R.menu.list_context_menu, menu)

        // Use the current item to create a custom view for the header.
        val info = menuInfo as AdapterContextMenuInfo
        val alarm = mAdapter.getItem(info.position)

        alarm?.run {
            // Construct the Calendar to compute the time.
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minutes)

            // Change the text based on the state of the alarm.
            if (isEnabled) {
                menu.findItem(R.id.enable_alarm).setTitle(R.string.disable_alarm)
            }
        }
    }
}