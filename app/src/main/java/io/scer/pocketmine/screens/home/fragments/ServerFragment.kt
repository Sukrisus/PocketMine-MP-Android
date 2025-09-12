package io.scer.pocketmine.screens.home.fragments

import android.content.Context.WIFI_SERVICE
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.PercentFormatter
import com.google.android.material.snackbar.Snackbar
import io.scer.pocketmine.R
import io.scer.pocketmine.ServerService
import io.scer.pocketmine.server.*

class ServerFragment : BaseFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_server, container, false)

    private lateinit var startButton: View
    private lateinit var stopButton: View
    private lateinit var chartProcessor: LineChart
    private lateinit var ipLabel: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val isStarted = Server.getInstance().isRunning
        toggleButtons(isStarted)

        startButton = view.findViewById(R.id.start)
        stopButton = view.findViewById(R.id.stop)
        chartProcessor = view.findViewById(R.id.chart_processor)
        ipLabel = view.findViewById(R.id.ip)

        startButton.setOnClickListener {
            if (!Server.getInstance().isInstalled) {
                Snackbar.make(requireView(), R.string.download_phar, Snackbar.LENGTH_SHORT).show()
                Thread {
                    try {
                        val url = java.net.URL("https://github.com/pmmp/PocketMine-MP/releases/download/5.33.1/PocketMine-MP.phar")
                        url.openStream().use { input ->
                            java.io.FileOutputStream(Server.getInstance().files.phar).use { output ->
                                input.copyTo(output)
                            }
                        }
                        requireActivity().runOnUiThread {
                            Snackbar.make(requireView(), R.string.download, Snackbar.LENGTH_SHORT).show()
                            service = Intent(activity, ServerService::class.java)
                            ContextCompat.startForegroundService(requireContext(), service!!)
                        }
                    } catch (e: Exception) {
                        requireActivity().runOnUiThread {
                            Snackbar.make(requireView(), R.string.download_error, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }.start()
            } else {
                service = Intent(activity, ServerService::class.java)
                ContextCompat.startForegroundService(requireContext(), service!!)
            }
        }

        stopButton.setOnClickListener {
            Server.getInstance().sendCommand("stop")
        }

        dataSet = LineDataSet(ArrayList<Entry>(), null)
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.color = ContextCompat.getColor(requireContext(), R.color.secondaryColor)
        dataSet.setDrawCircles(false)
        lineData = LineData(dataSet)
        chartProcessor.description.isEnabled = false
        chartProcessor.data = lineData
        chartProcessor.setScaleEnabled(false)
        chartProcessor.setTouchEnabled(false)
        chartProcessor.isDragEnabled = false
        chartProcessor.setDrawBorders(true)
        chartProcessor.legend.isEnabled = false
        val leftAxis = chartProcessor.axisLeft
        leftAxis.axisMaximum = 100f
        leftAxis.valueFormatter = PercentFormatter()
        leftAxis.setDrawGridLines(false)
        val rightAxis = chartProcessor.axisRight
        rightAxis.isEnabled = false
        val xAxis = chartProcessor.xAxis
        xAxis.isEnabled = false

        ipLabel.text = getIpAddress()
    }

    @Suppress("DEPRECATION")
    private fun getIpAddress(): String {
        val wifiManager = requireContext().applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return if (ip == 0) "127.0.0.1" else Formatter.formatIpAddress(ip)
    }

    private val startObserver = ServerBus.listen(StartEvent::class.java).subscribe({
        if (activity == null) return@subscribe

        requireActivity().runOnUiThread {
            toggleButtons(true)
        }
    }, ::handleError)

    private val stopObserver = ServerBus.listen(StopEvent::class.java).subscribe({
        if (activity == null) return@subscribe

        requireActivity().runOnUiThread {
            toggleButtons(false)
        }
        if (service != null) requireActivity().stopService(service)
    }, ::handleError)

    private val errorObserver = ServerBus.listen(ErrorEvent::class.java).subscribe ({
        if (activity == null) return@subscribe

        when (it.type) {
            Errors.PHAR_NOT_EXIST -> Snackbar.make(requireView(), R.string.phar_does_not_exist, Snackbar.LENGTH_LONG).show()
            Errors.UNKNOWN -> Snackbar.make(requireView(), "Error: $it.message", Snackbar.LENGTH_LONG).show()
        }
        requireActivity().runOnUiThread {
            toggleButtons(false)
        }
        if (service != null) requireActivity().stopService(service)
    }, ::handleError)

    private lateinit var dataSet: LineDataSet
    private lateinit var lineData: LineData
    private var lastIndex: Int = 0
    private val statUpdateObserver = ServerBus.listen(UpdateStatEvent::class.java).subscribe ({
        if (activity == null || !it.state.containsKey("Load")) return@subscribe

        requireActivity().runOnUiThread {
            val processor = it.state.getValue("Load").replace("%", "").toFloat()

            if (lastIndex >= 5) {
                dataSet.removeFirst()
            }

            if (lineData.entryCount > 0) {
                lastIndex++
                lineData.addEntry(Entry((lastIndex).toFloat(), processor), 0)
            } else {
                lineData.addEntry(Entry(0f, processor), 0)
            }

            dataSet.notifyDataSetChanged()
            chartProcessor.notifyDataSetChanged()
            chartProcessor.invalidate()
        }
    }, ::handleError)

    private fun toggleButtons(isStarted: Boolean) {
        if (startButton.isEnabled != !isStarted) {
            startButton.isEnabled = !isStarted
        }
        if (stopButton.isEnabled != isStarted) {
            stopButton.isEnabled = isStarted
        }
    }

    override fun onDestroyView() {
        startObserver.dispose()
        stopObserver.dispose()
        errorObserver.dispose()
        statUpdateObserver.dispose()
        super.onDestroyView()
    }

    companion object {
        private var service: Intent? = null
    }
}