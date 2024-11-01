// Copyright 2023 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package io.github.lime3ds.android.features.settings.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.PreferenceManager
import com.google.android.material.color.MaterialColors
import io.github.lime3ds.android.LimeApplication
import io.github.lime3ds.android.NativeLibrary
import io.github.lime3ds.android.R
import io.github.lime3ds.android.databinding.ActivitySettingsBinding
import io.github.lime3ds.android.features.settings.model.AbstractStringSetting
import java.io.IOException
import io.github.lime3ds.android.features.settings.model.BooleanSetting
import io.github.lime3ds.android.features.settings.model.FloatSetting
import io.github.lime3ds.android.features.settings.model.IntSetting
import io.github.lime3ds.android.features.settings.model.ScaledFloatSetting
import io.github.lime3ds.android.features.settings.model.Settings
import io.github.lime3ds.android.features.settings.model.SettingsViewModel
import io.github.lime3ds.android.features.settings.model.StringSetting
import io.github.lime3ds.android.features.settings.model.view.InputBindingSetting
import io.github.lime3ds.android.features.settings.utils.SettingsFile
import io.github.lime3ds.android.utils.SystemSaveGame
import io.github.lime3ds.android.utils.DirectoryInitialization
import io.github.lime3ds.android.utils.InsetsHelper
import io.github.lime3ds.android.utils.ThemeUtil

class SettingsActivity : AppCompatActivity(), SettingsActivityView {
    private val presenter = SettingsActivityPresenter(this)

    private lateinit var binding: ActivitySettingsBinding

    private val settingsViewModel: SettingsViewModel by viewModels()

    override val settings: Settings get() = settingsViewModel.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.setTheme(this)

        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val launcher = intent
        val gameID = launcher.getStringExtra(ARG_GAME_ID)
        val menuTag = launcher.getStringExtra(ARG_MENU_TAG)
        presenter.onCreate(savedInstanceState, menuTag!!, gameID!!)

        // Show "Back" button in the action bar for navigation
        setSupportActionBar(binding.toolbarSettings)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        if (InsetsHelper.getSystemGestureType(applicationContext) !=
            InsetsHelper.GESTURE_NAVIGATION
        ) {
            binding.navigationBarShade.setBackgroundColor(
                ThemeUtil.getColorWithOpacity(
                    MaterialColors.getColor(
                        binding.navigationBarShade,
                        com.google.android.material.R.attr.colorSurface
                    ),
                    ThemeUtil.SYSTEM_BAR_ALPHA
                )
            )
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateBack()
            }
        )

        setInsets()
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateBack()
        return true
    }

    private fun navigateBack() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Critical: If super method is not called, rotations will be busted.
        super.onSaveInstanceState(outState)
        presenter.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        presenter.onStart()
    }

    /**
     * If this is called, the user has left the settings screen (potentially through the
     * home button) and will expect their changes to be persisted. So we kick off an
     * IntentService which will do so on a background thread.
     */
    override fun onStop() {
        super.onStop()
        presenter.onStop(isFinishing)
    }

    override fun showSettingsFragment(menuTag: String, addToStack: Boolean, gameId: String) {
        if (!addToStack && settingsFragment != null) {
            return
        }

        val transaction = supportFragmentManager.beginTransaction()
        if (addToStack) {
            if (areSystemAnimationsEnabled()) {
                transaction.setCustomAnimations(
                    R.anim.anim_settings_fragment_in,
                    R.anim.anim_settings_fragment_out,
                    0,
                    R.anim.anim_pop_settings_fragment_out
                )
            }
            transaction.addToBackStack(null)
        }
        transaction.replace(
            R.id.frame_content,
            SettingsFragment.newInstance(menuTag, gameId),
            FRAGMENT_TAG
        )
        transaction.commit()
    }

    private fun areSystemAnimationsEnabled(): Boolean {
        val duration = android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        val transition = android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f
        )
        return duration != 0f && transition != 0f
    }

    override fun onSettingsFileLoaded() {
        val fragment: SettingsFragmentView? = settingsFragment
        fragment?.loadSettingsList()
    }

    override fun onSettingsFileNotFound() {
        val fragment: SettingsFragmentView? = settingsFragment
        fragment?.loadSettingsList()
    }

    override fun showToastMessage(message: String, isLong: Boolean) {
        Toast.makeText(
            this,
            message,
            if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    override fun onSettingChanged() {
        presenter.onSettingChanged()
    }

    fun onSettingsReset() {
        // Prevents saving to a non-existent settings file
        presenter.onSettingsReset()

        val controllerKeys = Settings.buttonKeys + Settings.circlePadKeys + Settings.cStickKeys +
                Settings.dPadAxisKeys + Settings.dPadButtonKeys + Settings.triggerKeys
        val editor =
            PreferenceManager.getDefaultSharedPreferences(LimeApplication.appContext).edit()
        controllerKeys.forEach { editor.remove(it) }
        editor.apply()

        // Reset the static memory representation of each setting
        BooleanSetting.clear()
        FloatSetting.clear()
        ScaledFloatSetting.clear()
        IntSetting.clear()
        StringSetting.clear()

        // Delete settings file because the user may have changed values that do not exist in the UI
        val settingsFile = SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
        if (!settingsFile.delete()) {
            throw IOException("Failed to delete $settingsFile")
        }

        // Set the root of the document tree before we create a new config file or the native code
        // will fail when creating the file.
        if (DirectoryInitialization.setLime3DSUserDirectory()) {
            LimeApplication.documentsTree.setRoot(Uri.parse(DirectoryInitialization.userPath))
            NativeLibrary.createConfigFile()
        } else {
            throw IllegalStateException("Lime3DS directory unavailable when accessing config file!")
        }

        // Set default values for system config file
        SystemSaveGame.apply {
            setUsername("LIME3DS C")
            setBirthday(12, 1)
            setSystemLanguage(1)
            setSoundOutputMode(1)
            setCountryCode(49)
            setPlayCoins(300)
        }

        // Set default key map
        setUpDefaultSettingKeys()

        showToastMessage(getString(R.string.settings_reset), true)
        finish()
    }

    fun setToolbarTitle(title: String) {
        binding.toolbarSettingsLayout.title = title
    }

    private val settingsFragment: SettingsFragment?
        get() = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as SettingsFragment?

    private fun setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.frameContent
        ) { view: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(
                left = barInsets.left + cutoutInsets.left,
                right = barInsets.right + cutoutInsets.right
            )

            val mlpAppBar = binding.appbarSettings.layoutParams as MarginLayoutParams
            mlpAppBar.leftMargin = barInsets.left + cutoutInsets.left
            mlpAppBar.rightMargin = barInsets.right + cutoutInsets.right
            binding.appbarSettings.layoutParams = mlpAppBar

            val mlpShade = binding.navigationBarShade.layoutParams as MarginLayoutParams
            mlpShade.height = barInsets.bottom
            binding.navigationBarShade.layoutParams = mlpShade

            windowInsets
        }
    }

    companion object {
        private const val ARG_MENU_TAG = "menu_tag"
        private const val ARG_GAME_ID = "game_id"
        private const val FRAGMENT_TAG = "settings"

        @JvmStatic
        fun launch(context: Context, menuTag: String?, gameId: String?) {
            val settings = Intent(context, SettingsActivity::class.java)
            settings.putExtra(ARG_MENU_TAG, menuTag)
            settings.putExtra(ARG_GAME_ID, gameId)
            context.startActivity(settings)
        }

        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            menuTag: String?,
            gameId: String?
        ) {
            val settings = Intent(context, SettingsActivity::class.java)
            settings.putExtra(ARG_MENU_TAG, menuTag)
            settings.putExtra(ARG_GAME_ID, gameId)
            launcher.launch(settings)
        }
    }

    //////////////////////////////////// Default Keymap Setting ///////////////////////////////
    private lateinit var preferences: SharedPreferences
    fun setUpDefaultSettingKeys() {
        preferences = PreferenceManager.getDefaultSharedPreferences(LimeApplication.appContext)
        Settings.buttonKeys.forEachIndexed { i: Int, key: String ->
            val button = getInputObject(key)
            val itemInputBindingSetting = InputBindingSetting(button, Settings.buttonTitles[i])
            when (key) {
                Settings.KEY_BUTTON_A -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        96
                    )
                )

                Settings.KEY_BUTTON_B -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        97
                    )
                )

                Settings.KEY_BUTTON_X -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        99
                    )
                )

                Settings.KEY_BUTTON_Y -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        100
                    )
                )

                Settings.KEY_BUTTON_SELECT -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        109
                    )
                )

                Settings.KEY_BUTTON_START -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        108
                    )
                )

                Settings.KEY_BUTTON_HOME -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        106
                    )
                ) // L3
            }
        }

        Settings.circlePadKeys.forEachIndexed { i: Int, key: String ->
            val button = getInputObject(key)
            val itemInputBindingSetting = InputBindingSetting(button, Settings.buttonTitles[i])
            when (key) {
                Settings.KEY_CIRCLEPAD_AXIS_VERTICAL -> itemInputBindingSetting.onMotionInputCube(
                    "retrogame_joypad", 1
                )

                Settings.KEY_CIRCLEPAD_AXIS_HORIZONTAL -> itemInputBindingSetting.onMotionInputCube(
                    "retrogame_joypad", 0
                )
            }
        }

        Settings.cStickKeys.forEachIndexed { i: Int, key: String ->
            val button = getInputObject(key)
            val itemInputBindingSetting = InputBindingSetting(button, Settings.buttonTitles[i])
            when (key) {
                Settings.KEY_CSTICK_AXIS_VERTICAL -> itemInputBindingSetting.onMotionInputCube(
                    "retrogame_joypad", 14
                )

                Settings.KEY_CSTICK_AXIS_HORIZONTAL -> itemInputBindingSetting.onMotionInputCube(
                    "retrogame_joypad", 11
                )
            }
        }

        Settings.dPadAxisKeys.forEachIndexed { i: Int, key: String ->
            val button = getInputObject(key)
            val itemInputBindingSetting = InputBindingSetting(button, Settings.buttonTitles[i])
            when (key) {
                Settings.KEY_DPAD_AXIS_VERTICAL -> itemInputBindingSetting.onMotionInputCube(
                    "retrogame_joypad", 16
                )

                Settings.KEY_DPAD_AXIS_HORIZONTAL -> itemInputBindingSetting.onMotionInputCube(
                    "retrogame_joypad", 15
                )
            }
        }

        Settings.triggerKeys.forEachIndexed { i: Int, key: String ->
            val button = getInputObject(key)
            val itemInputBindingSetting = InputBindingSetting(button, Settings.buttonTitles[i])
            when (key) {
                Settings.KEY_BUTTON_L -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        102
                    )
                )

                Settings.KEY_BUTTON_R -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        103
                    )
                )

                Settings.KEY_BUTTON_ZL -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        104
                    )
                )

                Settings.KEY_BUTTON_ZR -> itemInputBindingSetting.onKeyInput(
                    KeyEvent(
                        KeyEvent.ACTION_UP,
                        105
                    )
                )
            }
        }
    }

    private fun getInputObject(key: String): AbstractStringSetting {
        return object : AbstractStringSetting {
            override var string: String
                get() = preferences.getString(key, "")!!
                set(value) {
                    preferences.edit()
                        .putString(key, value)
                        .apply()
                }
            override val key = key
            override val section = Settings.SECTION_CONTROLS
            override val isRuntimeEditable = true
            override val valueAsString = preferences.getString(key, "")!!
            override val defaultValue = ""
        }
    }
}
