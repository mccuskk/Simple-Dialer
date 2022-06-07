package com.simplemobiletools.dialer.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.SearchManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.provider.CallLog
import android.provider.Settings
import android.telecom.Call
import android.telephony.PhoneNumberUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuItemCompat
import androidx.viewpager.widget.ViewPager
import com.google.android.material.snackbar.Snackbar
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.*
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.dialer.BLECommService
import com.simplemobiletools.dialer.BuildConfig
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.R.drawable
import com.simplemobiletools.dialer.R.id
import com.simplemobiletools.dialer.R.layout
import com.simplemobiletools.dialer.R.string
import com.simplemobiletools.dialer.adapters.ViewPagerAdapter
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.fragments.MyViewPagerFragment
import com.simplemobiletools.dialer.helpers.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_cati.*
import kotlinx.android.synthetic.main.fragment_contacts.*
import kotlinx.android.synthetic.main.fragment_recents.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.Long.Companion.MAX_VALUE

private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val LOCATION_PERMISSION_REQUEST_CODE = 2

fun Context.hasPermission(permissionType: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permissionType) ==
        PackageManager.PERMISSION_GRANTED
}

private fun Activity.requestPermission(permission: String, requestCode: Int) {
    ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
}

class MainActivity : SimpleActivity() {
    lateinit var client: Mqtt5AsyncClient
    lateinit var bluetoothAdapterName: String
    lateinit var dialingReceiver: BroadcastReceiver
    lateinit var answeredReceiver: BroadcastReceiver
    lateinit var readyReceiver: BroadcastReceiver

    private var wakeLock: PowerManager.WakeLock? = null
    private var current_state = ""

    private var isSearchOpen = false
    private var launchedDialer = false
    private var searchMenuItem: MenuItem? = null
    private var storedShowTabs = 0
    private var connected = false
    private var lastDisconnected: Long = MAX_VALUE

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 -> {
                if (grantResults.isNotEmpty() && grantResults[0] ==
                    PackageManager.PERMISSION_GRANTED) {
                    if ((ContextCompat.checkSelfPermission(this@MainActivity,
                            Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED)) {
                        Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show()
                }
                return
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock!!.release()
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_main)

        if (ContextCompat.checkSelfPermission(this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            } else {
                ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }

        appLaunched(BuildConfig.APPLICATION_ID)
        setupTabColors()

        launchedDialer = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false

        if (isDefaultDialer()) {
            checkContactPermissions()

            if (!Settings.canDrawOverlays(this)) {
                val snackbar = Snackbar.make(main_holder, string.allow_displaying_over_other_apps, Snackbar.LENGTH_INDEFINITE).setAction(string.ok) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
                snackbar.setBackgroundTint(getProperBackgroundColor().darkenColor())
                snackbar.setTextColor(getProperTextColor())
                snackbar.setActionTextColor(getProperTextColor())
                snackbar.show()
            }
        } else {
            launchSetDefaultDialerIntent()
        }

        val serviceIntent = Intent(this, BLECommService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        val callNotificationManager by lazy { CallNotificationManager(this) }
        callNotificationManager.setupNotification()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "co.kwest.www.callmanager:wake_lock")
        wakeLock!!.acquire(24 * 60 * MINUTE_SECONDS * 1000L)


        bluetoothAdapterName = BluetoothAdapter.getDefaultAdapter().name
        promptEnableBluetooth()

        val answeredFilter = IntentFilter()
        answeredFilter.addAction("co.kwest.www.callmanager.answered")
        answeredReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Signal to CATI the call has been answered
                val state = intent.getIntExtra("state", -1)

                if (state == Call.STATE_ACTIVE) {
                    current_state = "answered"
                    client.publishWith()
                        .topic("${bluetoothAdapterName}/phone")
                        .payload(current_state.encodeToByteArray())
                        .send()
                }
            }
        }
        registerReceiver(answeredReceiver, answeredFilter)

        val readyFilter = IntentFilter()
        readyFilter.addAction("co.kwest.www.callmanager.ready")
        readyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Signal to CATI that we are ready to dial another phone
                if (!current_state.equals("ready")) {
                    current_state = "ready"
                    client.publishWith()
                        .topic("${bluetoothAdapterName}/phone")
                        .payload(current_state.encodeToByteArray())
                        .send()
                }
            }
        }
        registerReceiver(readyReceiver, readyFilter)

        val dialingFilter = IntentFilter()
        dialingFilter.addAction("co.kwest.www.callmanager.dialing")
        dialingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Signal to CATI that we are dialing the phone
                current_state = "dialing"

                client.publishWith()
                    .topic("${bluetoothAdapterName}/phone")
                    .payload(current_state.encodeToByteArray())
                    .send()
            }
        }
        registerReceiver(dialingReceiver, dialingFilter)

        GlobalScope.launch {
            asyncClient()
        }
        hideTabs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, launchedDialer)
    }

    override fun onResume() {
        super.onResume()
        val properPrimaryColor = getProperPrimaryColor()
        val dialpadIcon = resources.getColoredDrawableWithColor(drawable.ic_dialpad_vector, properPrimaryColor.getContrastColor())
        main_dialpad_button.apply {
            setImageDrawable(dialpadIcon)
            background.applyColorFilter(properPrimaryColor)
        }

        main_tabs_holder.setBackgroundColor(getProperBackgroundColor())
        main_tabs_holder.setSelectedTabIndicatorColor(properPrimaryColor)

        if (viewpager.adapter != null) {
            getInactiveTabIndexes(viewpager.currentItem).forEach {
                main_tabs_holder.getTabAt(it)?.icon?.applyColorFilter(getProperTextColor())
            }

            main_tabs_holder.getTabAt(viewpager.currentItem)?.icon?.applyColorFilter(properPrimaryColor)
            getAllFragments().forEach {
                it?.setupColors(getProperTextColor(), getProperPrimaryColor(), getProperPrimaryColor())
            }
        }

        if (!isSearchOpen) {
            if (storedShowTabs != config.showTabs) {
                System.exit(0)
                return
            }
            refreshItems(true)
        }

        checkShortcuts()
        Handler().postDelayed({
            recents_fragment?.refreshItems()
        }, 2000)
    }

    override fun onPause() {
        super.onPause()
        storedShowTabs = config.showTabs
        config.lastUsedViewPagerPage = viewpager.currentItem
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        menu.apply {
            findItem(id.clear_call_history).isVisible = getCurrentFragment() == recents_fragment

            setupSearch(this)
            updateMenuItemColors(this)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            id.clear_call_history -> clearCallHistory()
            id.settings -> launchSettings()
            id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        // we dont really care about the result, the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkContactPermissions()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshItems()
    }

    private fun checkContactPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            initFragments()
        }
    }
    private fun promptEnableBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE)
        }
    }


    private fun setupSearch(menu: Menu) {
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchMenuItem = menu.findItem(id.search)
        (searchMenuItem!!.actionView as SearchView).apply {
            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            queryHint = getString(string.search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        getCurrentFragment()?.onSearchQueryChanged(newText)
                    }
                    return true
                }
            })
        }

        MenuItemCompat.setOnActionExpandListener(searchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                isSearchOpen = true
                main_dialpad_button.beGone()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                getCurrentFragment()?.onSearchClosed()
                isSearchOpen = false
                main_dialpad_button.beVisible()
                return true
            }
        })
    }

    private fun clearCallHistory() {
        ConfirmationDialog(this, "", string.clear_history_confirmation) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                runOnUiThread {
                    recents_fragment?.refreshItems()
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val launchDialpad = getLaunchDialpadShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(launchDialpad)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    private fun getLaunchDialpadShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(string.dialpad)
        val drawable = resources.getDrawable(drawable.shortcut_dialpad, applicationContext.theme)
        (drawable as LayerDrawable).findDrawableByLayerId(id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun setupTabColors() {
        val lastUsedPage = getDefaultTab()
        main_tabs_holder.apply {
            background = ColorDrawable(getProperBackgroundColor())
            setSelectedTabIndicatorColor(getProperPrimaryColor())
            getTabAt(lastUsedPage)?.select()
            getTabAt(lastUsedPage)?.icon?.applyColorFilter(getProperPrimaryColor())

            getInactiveTabIndexes(lastUsedPage).forEach {
                getTabAt(it)?.icon?.applyColorFilter(getProperTextColor())
            }
        }

        main_tabs_holder.onTabSelectionChanged(
            tabUnselectedAction = {
                it.icon?.applyColorFilter(getProperTextColor())
            },
            tabSelectedAction = {
                viewpager.currentItem = it.position
                it.icon?.applyColorFilter(getProperPrimaryColor())
            }
        )
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until tabsList.size).filter { it != activeIndex }

    private fun initFragments() {
        viewpager.offscreenPageLimit = 2
        viewpager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
                searchMenuItem?.collapseActionView()
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                main_tabs_holder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
                invalidateOptionsMenu()
            }
        })

        // selecting the proper tab sometimes glitches, add an extra selector to make sure we have it right
        main_tabs_holder.onGlobalLayout {
            Handler().postDelayed({
                var wantedTab = getDefaultTab()

                // open the Recents tab if we got here by clicking a missed call notification
                if (intent.action == Intent.ACTION_VIEW && config.showTabs and TAB_CALL_HISTORY > 0) {
                    wantedTab = main_tabs_holder.tabCount - 1

                    ensureBackgroundThread {
                        resetMissedCalls()
                    }
                }

                main_tabs_holder.getTabAt(wantedTab)?.select()
                invalidateOptionsMenu()
            }, 100L)
        }

        main_dialpad_button.setOnClickListener {
            launchDialpad()
        }

        if (config.openDialPadAtLaunch && !launchedDialer) {
            launchDialpad()
            launchedDialer = true
        }
    }

    private fun hideTabs() {
        val selectedTabIndex = main_tabs_holder.selectedTabPosition
        viewpager.adapter = null
        main_tabs_holder.removeAllTabs()
        var skippedTabs = 0
        var isAnySelected = false
        tabsList.forEachIndexed { index, value ->
            if (config.showTabs and value == 0) {
                skippedTabs++
            } else {
                val tab = main_tabs_holder.newTab().setIcon(getTabIcon(index))
                tab.contentDescription = getTabContentDescription(index)
                val wasAlreadySelected = selectedTabIndex > -1 && selectedTabIndex == index - skippedTabs
                val shouldSelect = !isAnySelected && wasAlreadySelected
                if (shouldSelect) {
                    isAnySelected = true
                }
                main_tabs_holder.addTab(tab, index - skippedTabs, shouldSelect)
            }
        }
        if (!isAnySelected) {
            main_tabs_holder.selectTab(main_tabs_holder.getTabAt(getDefaultTab()))
        }
        main_tabs_holder.beGoneIf(main_tabs_holder.tabCount == 1)
        storedShowTabs = config.showTabs
    }

    private fun getTabIcon(position: Int): Drawable {
        val drawableId = when (position) {
            0 -> drawable.ic_person_vector
            1 -> drawable.ic_star_vector
            else -> drawable.ic_clock_vector
        }

        return resources.getColoredDrawableWithColor(drawableId, getProperTextColor())
    }

    private fun getTabContentDescription(position: Int): String {
        val stringId = when (position) {
            0 -> string.contacts_tab
            1 -> string.cati_tab
            else -> string.call_history_tab
        }

        return resources.getString(stringId)
    }

    private fun refreshItems(openLastTab: Boolean = false) {
        if (isDestroyed || isFinishing) {
            return
        }

        if (viewpager.adapter == null) {
            viewpager.adapter = ViewPagerAdapter(this)
            viewpager.currentItem = if (openLastTab) main_tabs_holder.selectedTabPosition else getDefaultTab()
            viewpager.onGlobalLayout {
                refreshFragments()
            }
        } else {
            refreshFragments()
        }
    }

    private fun launchDialpad() {
        Intent(applicationContext, DialpadActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun refreshFragments() {
        contacts_fragment?.refreshItems()
        cati_fragment?.refreshItems()
        recents_fragment?.refreshItems()
    }

    private fun getAllFragments(): ArrayList<MyViewPagerFragment?> {
        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment?>()

        if (showTabs and TAB_CONTACTS > 0) {
            fragments.add(contacts_fragment)
        }

        if (showTabs and TAB_FAVORITES > 0) {
            fragments.add(cati_fragment)
        }

        if (showTabs and TAB_CALL_HISTORY > 0) {
            fragments.add(recents_fragment)
        }

        return fragments
    }

    private fun getCurrentFragment(): MyViewPagerFragment? = getAllFragments().getOrNull(viewpager.currentItem)

    private fun getDefaultTab(): Int {
        val showTabsMask = config.showTabs
        return when (config.defaultTab) {
            TAB_LAST_USED -> if (config.lastUsedViewPagerPage < main_tabs_holder.tabCount) config.lastUsedViewPagerPage else 0
            TAB_CONTACTS -> 0
            TAB_CATI -> if (showTabsMask and TAB_CONTACTS > 0) 1 else 0
            else -> {
                if (showTabsMask and TAB_CALL_HISTORY > 0) {
                    if (showTabsMask and TAB_CONTACTS > 0) {
                        if (showTabsMask and TAB_CATI > 0) {
                            2
                        } else {
                            1
                        }
                    } else {
                        if (showTabsMask and TAB_CATI > 0) {
                            1
                        } else {
                            0
                        }
                    }
                } else {
                    0
                }
            }
        }
    }

    public fun connectionColor(): Int {
        return Color.parseColor(if (connected) "#05ff50" else "#ffffff")
    }

    // clear the missed calls count. Doesn't seem to always work, but having it can't hurt
    // found at https://android.googlesource.com/platform/packages/apps/Dialer/+/nougat-release/src/com/android/dialer/calllog/MissedCallNotifier.java#181
    private fun resetMissedCalls() {
        val values = ContentValues().apply {
            put(CallLog.Calls.NEW, 0)
            put(CallLog.Calls.IS_READ, 1)
        }

        val selection = "${CallLog.Calls.TYPE} = ?"
        val selectionArgs = arrayOf(CallLog.Calls.MISSED_TYPE.toString())

        try {
            val uri = CallLog.Calls.CONTENT_URI
            contentResolver.update(uri, values, selection, selectionArgs)
        } catch (e: Exception) {
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_GLIDE or LICENSE_INDICATOR_FAST_SCROLL

        val faqItems = arrayListOf(
            FAQItem(string.faq_2_title_commons, string.faq_2_text_commons),
            FAQItem(string.faq_6_title_commons, string.faq_6_text_commons),
            FAQItem(string.faq_7_title_commons, string.faq_7_text_commons),
            FAQItem(string.faq_9_title_commons, string.faq_9_text_commons)
        )

        startAboutActivity(string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    suspend fun asyncClient() =  withContext(Dispatchers.Default) {

        /* Note that we must have added the bluetoothAdapterName with the password to the mosquitto server using
         * mosquitto_passwd /etc/mosquitto/passwd <bluetoothAdapterName> and restarted it before we are good to go.
         */

        client = Mqtt5Client.builder()
            .identifier(bluetoothAdapterName)
            .serverHost("mqtt.kwest.co")
            .serverPort(8883)
            .sslWithDefaultConfig()
            .simpleAuth()
            .username(bluetoothAdapterName)
            .password("3317b699-585c-49eb-8708-aa9700dd66f3".toByteArray())
            .applySimpleAuth()
            .addDisconnectedListener(object : MqttClientDisconnectedListener {
                override fun onDisconnected(context: MqttClientDisconnectedContext) {
                    connected = false
                    refreshFragments()
                    context.reconnector.reconnect(context.source != MqttDisconnectSource.USER).delay((2500 + (500 * context.reconnector.attempts)).toLong(), TimeUnit.MILLISECONDS)
                    lastDisconnected = System.currentTimeMillis()
                }
            })
            .addConnectedListener(object : MqttClientConnectedListener {
                override fun onConnected(context: MqttClientConnectedContext) {
                    connected = true
                    refreshFragments()

                    if (System.currentTimeMillis() - lastDisconnected in 60000..120000) {
                        client.publishWith()
                            .topic("${bluetoothAdapterName}/phone")
                            .payload("restart".encodeToByteArray())
                            .send()
                    }

                    client.subscribeWith()
                        .topicFilter("${bluetoothAdapterName}/call")
                        .qos(MqttQos.EXACTLY_ONCE)
                        .callback {
                             val phone = PhoneNumberUtils.formatNumber(it.payloadAsBytes.decodeToString(), Locale.getDefault().country)
                             this@MainActivity.launchCallIntent(phone)
                        }
                        .send()

                    client.subscribeWith()
                        .topicFilter("${bluetoothAdapterName}/hangup")
                        .qos(MqttQos.EXACTLY_ONCE)
                        .callback {
                            val intent = Intent()
                            intent.action = "co.kwest.www.callmanager.hangup"
                            this@MainActivity.sendBroadcast(intent)
                        }
                        .send()
                }
            })
            .buildAsync()

        client.connectWith()
            .cleanStart(true)
            .keepAlive(60)
            .send()

    }

}
