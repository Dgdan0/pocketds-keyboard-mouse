package com.pocketds.kbm

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Display
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.pocketds.kbm.accessibility.CursorAccessibilityService
import com.pocketds.kbm.debug.DebugLog
import com.pocketds.kbm.ime.BottomPanelService
import com.pocketds.kbm.settings.CursorSettings
import com.pocketds.kbm.settings.ScrollSettings
import com.pocketds.kbm.settings.ThemeSettings

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    companion object {
        private const val TAG = "PocketDS"
        private const val ONEPASSWORD_PACKAGE = "com.onepassword.android"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyNightMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        val serviceIntent = Intent(this, BottomPanelService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        findViewById<Button>(R.id.btnEnableKeyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btnSwitchKeyboard).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<EditText>(R.id.testField)

        findViewById<CheckBox>(R.id.checkInvertScroll).apply {
            isChecked = ScrollSettings.isInverted(context)
            setOnCheckedChangeListener { _, isChecked -> ScrollSettings.setInverted(context, isChecked) }
        }

        setUpThemeRadioGroup()

        findViewById<Button>(R.id.btnAutofillSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        findViewById<Button>(R.id.btnOpenOnePassword).setOnClickListener {
            openOnePasswordOnBottomScreen()
        }

        setUpCursorOptions()
        setUpDebugTrace()
    }

    private fun setUpCursorOptions() {
        val idleGroup = findViewById<RadioGroup>(R.id.cursorIdleGroup)
        val idToSeconds = mapOf(
            R.id.radioIdle5 to 5,
            R.id.radioIdle10 to 10,
            R.id.radioIdle15 to 15,
            R.id.radioIdle30 to 30,
            R.id.radioIdleNever to CursorSettings.IDLE_NEVER
        )
        val current = CursorSettings.idleHideSeconds(this)
        idleGroup.check(idToSeconds.entries.firstOrNull { it.value == current }?.key ?: R.id.radioIdle10)
        idleGroup.setOnCheckedChangeListener { _, id ->
            idToSeconds[id]?.let { CursorSettings.setIdleHideSeconds(this, it) }
        }

        findViewById<CheckBox>(R.id.checkHideOnScreenTouch).apply {
            isChecked = CursorSettings.hideOnScreenTouch(context)
            setOnCheckedChangeListener { _, isChecked ->
                CursorSettings.setHideOnScreenTouch(context, isChecked)
            }
        }
    }

    private fun setUpDebugTrace() {
        val trace = findViewById<TextView>(R.id.debugTrace)
        trace.movementMethod = ScrollingMovementMethod()
        val render = {
            val lines = DebugLog.snapshot()
            trace.text = if (lines.isEmpty()) "(nothing recorded yet)" else lines.joinToString("\n")
            // Jump to the newest entry, which is what you want to see after
            // something just went wrong.
            trace.post {
                val overflow = trace.layout?.height?.minus(trace.height) ?: 0
                if (overflow > 0) trace.scrollTo(0, overflow)
            }
        }
        render()
        findViewById<Button>(R.id.btnRefreshDebug).setOnClickListener { render() }
        findViewById<Button>(R.id.btnClearDebug).setOnClickListener {
            DebugLog.clear()
            render()
        }
        findViewById<Button>(R.id.btnCopyDebug).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("PocketDS debug trace", DebugLog.snapshot().joinToString("\n"))
            )
        }
    }

    private fun applyNightMode() {
        val mode = when (ThemeSettings.getMode(this)) {
            ThemeSettings.Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeSettings.Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeSettings.Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun setUpThemeRadioGroup() {
        val group = findViewById<RadioGroup>(R.id.themeRadioGroup)
        val checkedId = when (ThemeSettings.getMode(this)) {
            ThemeSettings.Mode.SYSTEM -> R.id.radioThemeSystem
            ThemeSettings.Mode.LIGHT -> R.id.radioThemeLight
            ThemeSettings.Mode.DARK -> R.id.radioThemeDark
        }
        group.check(checkedId)
        group.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.radioThemeLight -> ThemeSettings.Mode.LIGHT
                R.id.radioThemeDark -> ThemeSettings.Mode.DARK
                else -> ThemeSettings.Mode.SYSTEM
            }
            ThemeSettings.setMode(this, mode)
            // setDefaultNightMode() already recreates this activity on its own when the
            // effective mode changes — an explicit recreate() here double-fired it,
            // which is what was showing up as a flicker.
            applyNightMode()
            startService(Intent(this, BottomPanelService::class.java).setAction(BottomPanelService.ACTION_REFRESH_THEME))
        }
    }

    private fun openOnePasswordOnBottomScreen() {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val secondaryDisplayId = displayManager.displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.displayId
            ?: Display.DEFAULT_DISPLAY

        val intent = packageManager.getLaunchIntentForPackage(ONEPASSWORD_PACKAGE)
        if (intent == null) {
            Log.w(TAG, "1Password ($ONEPASSWORD_PACKAGE) isn't installed")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(secondaryDisplayId) }
        startActivity(intent, options.toBundle())
    }

    override fun onResume() {
        super.onResume()
        val accessibilityOn = CursorAccessibilityService.instance != null
        statusText.text = "Cursor control service: ${if (accessibilityOn) "ENABLED" else "not enabled yet"}"
    }
}
