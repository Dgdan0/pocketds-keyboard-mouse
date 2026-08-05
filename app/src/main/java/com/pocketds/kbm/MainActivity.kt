package com.pocketds.kbm

import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.pocketds.kbm.ime.BottomPanelService
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
