package cl.dlopez.grabadoroculto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cl.dlopez.grabadoroculto.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("config", Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true) {
                startRecording()
            } else {
                Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPrefs()
        binding.toggle.setOnClickListener { onToggle() }
        binding.battery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RecorderState.status.collect(::render) }
                launch {
                    RecorderState.message.collect { msg ->
                        binding.message.text = msg ?: ""
                        binding.message.visibility = if (msg == null) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.battery.visibility = if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE
    }

    private fun render(status: RecorderState.Status) {
        val idle = status == RecorderState.Status.Idle
        binding.options.alpha = if (idle) 1f else 0.4f
        setOptionsEnabled(idle)
        when (status) {
            RecorderState.Status.Idle -> {
                binding.status.setText(R.string.status_idle)
                binding.toggle.setText(R.string.start)
                binding.toggle.isEnabled = true
                binding.chrono.stop()
                binding.chrono.visibility = View.INVISIBLE
            }
            RecorderState.Status.Starting -> {
                binding.status.setText(R.string.status_starting)
                binding.toggle.setText(R.string.stop)
                binding.toggle.isEnabled = true
            }
            is RecorderState.Status.Recording -> {
                binding.status.setText(R.string.status_recording)
                binding.toggle.setText(R.string.stop)
                binding.toggle.isEnabled = true
                binding.chrono.base = status.startedAtElapsed
                binding.chrono.visibility = View.VISIBLE
                binding.chrono.start()
            }
        }
    }

    private fun setOptionsEnabled(enabled: Boolean) {
        listOf(
            binding.camBack, binding.camFront, binding.audio,
            binding.qUhd, binding.qFhd, binding.qHd
        ).forEach { it.isEnabled = enabled }
    }

    private fun onToggle() {
        if (RecorderState.status.value == RecorderState.Status.Idle) {
            savePrefs()
            val missing = requiredPermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startRecording() else permissionLauncher.launch(missing.toTypedArray())
        } else {
            RecorderService.stop(this)
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (binding.audio.isChecked) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startRecording() {
        RecorderService.start(
            this,
            front = binding.camFront.isChecked,
            audio = binding.audio.isChecked,
            quality = selectedQuality()
        )
    }

    private fun selectedQuality() = when (binding.quality.checkedRadioButtonId) {
        R.id.qUhd -> "UHD"
        R.id.qHd -> "HD"
        else -> "FHD"
    }

    private fun loadPrefs() {
        binding.camFront.isChecked = prefs.getBoolean("front", false)
        binding.camBack.isChecked = !binding.camFront.isChecked
        binding.audio.isChecked = prefs.getBoolean("audio", true)
        binding.quality.check(
            when (prefs.getString("quality", "FHD")) {
                "UHD" -> R.id.qUhd
                "HD" -> R.id.qHd
                else -> R.id.qFhd
            }
        )
    }

    private fun savePrefs() {
        prefs.edit()
            .putBoolean("front", binding.camFront.isChecked)
            .putBoolean("audio", binding.audio.isChecked)
            .putString("quality", selectedQuality())
            .apply()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
