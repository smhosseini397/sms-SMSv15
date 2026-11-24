package com.farshekashan.smsh

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.ClipData
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.text.TextWatcher
import android.text.Editable
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var topSearchButton: TextView
    private lateinit var topCallButton: TextView
    private lateinit var mainScroll: ScrollView
    private lateinit var selectionBar: LinearLayout
    private lateinit var conversationComposer: LinearLayout
    private lateinit var conversationInput: EditText
    private var selectedTab = "inbox"
    private var selectedContactNumber: String? = null
    private var openedAddress: String? = null
    private var conversationReturnTab = "inbox"
    private var pendingSearchMessageId: Long? = null
    private var lastSearchQuery: String = ""
    // Search opened from a conversation toolbar is strictly scoped to that conversation.
    private var conversationSearchQuery: String = ""
    private val contactRequest = 41
    private val permissionRequest = 42
    private val roleRequest = 43
    @Volatile private var inboxError: String? = null
    private var inboxLoadId = 0
    @Volatile private var inboxWorker: Thread? = null
    private val contactNameCache = HashMap<String, String>()
    @Volatile private var contactsCacheLoaded = false

    private val blue = Color.rgb(26, 115, 232)
    private var isDarkTheme = false
    private var ink = Color.rgb(32, 33, 36)
    private var muted = Color.rgb(95, 99, 104)
    private var page = Color.rgb(248, 249, 250)
    private var surface = Color.WHITE
    private var field = Color.rgb(241, 243, 244)
    private var dividerColor = Color.rgb(232, 234, 237)
    private var outgoingBubble = Color.rgb(232, 240, 254)
    private var inboxThreads: List<InboxThread> = emptyList()
    private var displayedThreadCount = 0
    private var loadMoreButton: View? = null
    // Full SMS archive is loaded once per process and reused for inbox and conversations.
    @Volatile private var smsArchiveCache: List<MessageRow>? = null
    @Volatile private var smsThreadCache: List<InboxThread>? = null
    @Volatile private var smsByAddressCache: Map<String, List<MessageRow>>? = null
    private val archiveLock = Any()
    private var conversationRows: List<MessageRow> = emptyList()
    private var conversationStartIndex = 0
    private var olderMessagesButton: View? = null
    private var searchMatchIdsByAddress: Map<String, List<Long>> = emptyMap()
    private var activeSearchMessageId: Long? = null
    private var messageFontScale = 1.0f
    private val baseTextSizes = java.util.WeakHashMap<TextView, Float>()
    private var activeDraftAddress: String? = null
    private val draftWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            activeDraftAddress?.let { address ->
                getSharedPreferences("sms_drafts", MODE_PRIVATE).edit()
                    .putString(address, s?.toString().orEmpty()).apply()
            }
        }
        override fun afterTextChanged(s: Editable?) {}
    }
    private var smsEventsReceiverRegistered = false
    private val smsEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SMS_STATUS_CHANGED) {
                val id = intent.getLongExtra(EXTRA_SMS_ROW_ID, -1L)
                val status = intent.getIntExtra("sms_status", Telephony.Sms.STATUS_NONE)
                val type = intent.getIntExtra("sms_type", Telephony.Sms.MESSAGE_TYPE_SENT)
                if (id > 0L) updateMessageStatusInMemory(id, status, type)
                return
            }
            if (intent.action != ACTION_SMS_DATA_CHANGED) return

            val id = intent.getLongExtra(EXTRA_SMS_ROW_ID, -1L)
            val address = intent.getStringExtra("sms_address") ?: return
            val body = intent.getStringExtra("sms_body") ?: return
            val date = intent.getLongExtra("sms_date", System.currentTimeMillis())
            if (id <= 0L) return

            val row = MessageRow(
                id, address, body, date, false,
                Telephony.Sms.MESSAGE_TYPE_INBOX, 0
            )

            // Update the in-memory archive; do not invalidate or reread the SMS provider.
            val archive = smsArchiveCache
            if (archive != null) {
                smsArchiveCache = (archive.filterNot { it.id == id } + row)
                    .sortedByDescending { it.date }
            }

            val byAddress = smsByAddressCache
            if (byAddress != null) {
                val updatedConversation = (byAddress[address].orEmpty()
                    .filterNot { it.id == id } + row).sortedBy { it.date }
                smsByAddressCache = byAddress + (address to updatedConversation)
            }

            // Update the existing inbox summary incrementally; never regroup the full archive.
            val cachedThreads = smsThreadCache
            if (cachedThreads != null) {
                val existingThread = cachedThreads.firstOrNull { it.address == address }
                smsThreadCache = if (existingThread == null) {
                    (cachedThreads + InboxThread(address, row, 1, true))
                        .sortedByDescending { it.latest.date }
                } else {
                    cachedThreads.map { thread ->
                        if (thread.address == address) {
                            thread.copy(latest = row, unread = thread.unread + 1)
                        } else thread
                    }.sortedByDescending { it.latest.date }
                }
            }

            if (openedAddress != null) {
                if (openedAddress == address) showConversation(address)
            } else if (selectedTab == "inbox") {
                showInbox()
            }
        }
    }
    private var threadSelectionMode = false
    private val selectedThreadAddresses = linkedSetOf<String>()
    private var messageSelectionMode = false
    private val selectedMessageIds = linkedSetOf<Long>()

    private fun applyThemePalette() {
        val themeMode = getSharedPreferences("smsh_ui", MODE_PRIVATE).getInt("theme_mode", 0)
        isDarkTheme = when (themeMode) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        if (isDarkTheme) {
            ink = Color.rgb(232, 234, 237)
            muted = Color.rgb(189, 193, 198)
            page = Color.rgb(18, 19, 22)
            surface = Color.rgb(32, 33, 36)
            field = Color.rgb(48, 49, 52)
            dividerColor = Color.rgb(60, 64, 67)
            outgoingBubble = Color.rgb(39, 57, 82)
        } else {
            ink = Color.rgb(32, 33, 36)
            muted = Color.rgb(95, 99, 104)
            page = Color.rgb(248, 249, 250)
            surface = Color.WHITE
            field = Color.rgb(241, 243, 244)
            dividerColor = Color.rgb(232, 234, 237)
            outgoingBubble = Color.rgb(232, 240, 254)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uiPrefs = getSharedPreferences("smsh_ui", MODE_PRIVATE)
        messageFontScale = uiPrefs.getFloat("message_font_scale", 1.0f).coerceIn(0.75f, 2.2f)
        applyThemePalette()
        window.statusBarColor = surface
        window.navigationBarColor = page
        window.decorView.systemUiVisibility = if (isDarkTheme) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildShell()
        showInbox()
        // Let the first frame render before opening the system SMS-role chooser.
        root.post { root.postDelayed({ requestSmsRoleIfNeeded() }, 300) }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (openedAddress != null) {
            val returnTab = conversationReturnTab
            openedAddress = null
            pendingSearchMessageId = null
            when (returnTab) {
                "search" -> { showSearch(); if (lastSearchQuery.isNotBlank()) performMessageSearch(lastSearchQuery) }
                "spam" -> showSpam()
                else -> { selectedTab = "inbox"; showInbox() }
            }
            return
        }
        if (selectedTab == "search" || selectedTab == "spam") {
            openedAddress = null
            selectedTab = "inbox"
            showInbox()
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (!smsEventsReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_SMS_DATA_CHANGED)
                addAction(ACTION_SMS_STATUS_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(smsEventsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(smsEventsReceiver, filter)
            }
            smsEventsReceiverRegistered = true
        }
        refreshSmsReceivedWhileAway()
    }

    /**
     * The dynamic SMS broadcast receiver is intentionally unregistered while the
     * Activity is paused. Catch up only messages newer than the RAM archive when
     * the user returns, without rescanning the complete SMS database.
     */
    private fun refreshSmsReceivedWhileAway() {
        val archive = smsArchiveCache ?: return
        val latestDate = archive.maxOfOrNull { it.date } ?: return
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ,
            Telephony.Sms.STATUS
        )
        val received = ArrayList<MessageRow>()
        try {
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE}>=?",
                arrayOf((latestDate - 1000L).toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeCol = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readCol = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                val statusCol = cursor.getColumnIndex(Telephony.Sms.STATUS)
                while (cursor.moveToNext()) {
                    val row = MessageRow(
                        cursor.getLong(idCol),
                        cursor.getString(addressCol) ?: "ناشناس",
                        cursor.getString(bodyCol) ?: "",
                        cursor.getLong(dateCol),
                        false,
                        cursor.getInt(typeCol),
                        cursor.getInt(readCol),
                        if (statusCol >= 0) cursor.getInt(statusCol) else -1
                    )
                    if (row.date >= latestDate && row.type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                        received.add(row)
                    }
                }
            } ?: return
        } catch (_: Exception) {
            return
        }

        if (received.isEmpty()) return
        val merged = (archive.filterNot { old -> received.any { it.id == old.id } } + received)
            .sortedByDescending { it.date }
        smsArchiveCache = merged
        smsByAddressCache = merged.groupBy { it.address }
            .mapValues { (_, rows) -> rows.sortedBy { it.date } }
        try {
            val store = SmsStore(this)
            received.forEach { store.upsertCachedMessage(it) }
        } catch (_: Exception) {}

        // Rebuild the visible screen from the updated in-memory data only.
        smsThreadCache = null
        val currentAddress = openedAddress
        if (currentAddress != null) {
            showConversation(currentAddress)
        } else if (selectedTab == "inbox") {
            showInbox()
        }
    }

    override fun onPause() {
        if (smsEventsReceiverRegistered) {
            try { unregisterReceiver(smsEventsReceiver) } catch (_: Exception) {}
            smsEventsReceiverRegistered = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        inboxWorker?.interrupt()
        inboxWorker = null
        super.onDestroy()
    }

    private fun isDefaultSmsApp(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            manager != null && manager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                manager.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }
    }

    private fun requestSmsRoleIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (manager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    requestRuntimePermissions()
                } else {
                    startActivityForResult(
                        manager.createRequestRoleIntent(RoleManager.ROLE_SMS), roleRequest
                    )
                }
            } else {
                inboxError = "امکان انتخاب نقش برنامه پیامک در این دستگاه وجود ندارد."
                showCurrentScreen()
            }
        } else if (!isDefaultSmsApp()) {
            startActivityForResult(
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName),
                roleRequest
            )
        } else {
            requestRuntimePermissions()
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), permissionRequest)
        else showCurrentScreen()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == roleRequest) {
            // Android 10+ role grants must be checked through RoleManager, not
            // Telephony.Sms.getDefaultSmsPackage(), which can report stale/null.
            if (isDefaultSmsApp()) {
                requestRuntimePermissions()
            } else {
                inboxError = "انتخاب برنامه پیش‌فرض پیامک تأیید نشد. برای خواندن و مدیریت پیامک‌ها، SMH را برنامه پیش‌فرض پیامک کنید."
                showCurrentScreen()
                toast("برای ادامه، SMH باید برنامه پیش‌فرض پیامک باشد.")
            }
        } else if (requestCode == contactRequest && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                ?.use { if (it.moveToFirst()) { selectedContactNumber = it.getString(0); composeMessage(selectedContactNumber) } }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            val readGranted = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
            if (!readGranted) {
                inboxError = "مجوز خواندن پیامک تأیید نشد. از تنظیمات برنامه، دسترسی پیامک را فعال کنید."
            } else {
                inboxError = null
            }
            showCurrentScreen()
        }
    }

    private fun showCurrentScreen() {
        if (openedAddress != null) showConversation(openedAddress!!)
        else when (selectedTab) {
            "spam" -> showSpam()
            "search" -> showSearch()
            else -> showInbox()
        }
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(page)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(surface)
        }
        val logo = TextView(this).apply {
            text = "●"; textSize = 25f; setTextColor(blue); gravity = Gravity.CENTER
        }
        top.addView(logo, LinearLayout.LayoutParams(dp(34), dp(42)))
        titleView = TextView(this).apply {
            text = "پیام‌ها"; textSize = 22f; setTextColor(ink); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        topSearchButton = iconTextButton("⌕") {
            val address = openedAddress
            if (address != null) showConversationSearchDialog(address)
            else { selectedTab = "search"; showSearch() }
        }
        topCallButton = iconTextButton("☎") { openedAddress?.let { callContact(it) } }.apply {
            visibility = View.GONE
            contentDescription = "تماس با مخاطب"
        }
        top.addView(topSearchButton)
        top.addView(topCallButton)
        top.addView(iconTextButton("⋮") { showAppMenu() })
        root.addView(top)

        // Selection actions stay pinned below the app header, outside the scroll view.
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(surface)
            visibility = View.GONE
        }
        root.addView(selectionBar, LinearLayout.LayoutParams(-1, -2))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        mainScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(mainScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Persistent inline composer, shown only while a conversation is open.
        conversationComposer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(surface)
            visibility = View.GONE
        }
        conversationInput = EditText(this).apply {
            hint = "پیامک"
            textSize = 16f
            minLines = 1
            maxLines = 5
            setSingleLine(false)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_NONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(field, 26)
            setTextColor(ink)
            setHintTextColor(muted)
        }
        conversationComposer.addView(conversationInput, LinearLayout.LayoutParams(0, -2, 1f))
        val sendButton = TextView(this).apply {
            text = "➤"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = oval(blue)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
            setOnClickListener { sendConversationMessage() }
        }
        conversationComposer.addView(sendButton)
        root.addView(conversationComposer, LinearLayout.LayoutParams(-1, -2))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setBackgroundColor(surface)
        }
        bottom.addView(bottomButton("پیام‌ها", "✉") { openedAddress = null; selectedTab = "inbox"; showInbox() })
        bottom.addView(bottomButton("تبلیغات", "▣") { openedAddress = null; selectedTab = "spam"; showSpam() })
        bottom.addView(bottomButton("پیام جدید", "+") { composeMessage() })
        root.addView(bottom)

        // Android 15 enforces edge-to-edge for apps targeting SDK 35.
        // Keep the header and bottom navigation clear of the system bars.
        val baseTopPadding = dp(12)
        val baseBottomPadding = dp(7)
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                insets.systemWindowInsetTop
            }
            @Suppress("DEPRECATION")
            val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                insets.systemWindowInsetBottom
            }
            val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else 0
            val keyboardVisible = imeBottom > bottomInset
            // Give the keyboard the bottom area: hide navigation tabs so the composer
            // remains immediately above the keyboard instead of being pushed underneath it.
            bottom.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            // On edge-to-edge Android versions the IME may overlay rather than resize
            // the root view; reserve its full inset so the composer stays above it.
            root.setPadding(0, 0, 0, if (keyboardVisible) imeBottom else 0)
            top.setPadding(dp(18), baseTopPadding + topInset, dp(18), dp(12))
            bottom.setPadding(dp(4), dp(7), dp(4), baseBottomPadding + bottomInset)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun iconTextButton(symbol: String, action: () -> Unit) = TextView(this).apply {
        text = symbol; textSize = 27f; gravity = Gravity.CENTER; setTextColor(muted)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
    }

    private fun bottomButton(label: String, symbol: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(TextView(this@MainActivity).apply { text = symbol; textSize = 21f; gravity = Gravity.CENTER; setTextColor(blue) })
        addView(TextView(this@MainActivity).apply { text = label; textSize = 11f; gravity = Gravity.CENTER; setTextColor(ink) })
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f)
    }

    private fun reset(title: String) {
        inboxLoadId++
        // Do not discard or cancel the archive when navigating between screens.
        // The worker may finish loading it and the next screen reuses the cache.
        displayedThreadCount = 0
        loadMoreButton = null
        content.removeAllViews()
        if (::selectionBar.isInitialized) { selectionBar.removeAllViews(); selectionBar.visibility = View.GONE }
        // ScrollView keeps its previous scrollY when the child content is rebuilt.
        // Reset both immediately and after layout so the inbox always opens at newest.
        if (::mainScroll.isInitialized) {
            mainScroll.scrollTo(0, 0)
            mainScroll.post { mainScroll.scrollTo(0, 0) }
        }
        if (::conversationComposer.isInitialized) conversationComposer.visibility = View.GONE
        titleView.text = title
        if (::topSearchButton.isInitialized) {
            topSearchButton.text = "⌕"
            topSearchButton.contentDescription = "جست‌وجوی همه پیام‌ها"
            topSearchButton.setOnClickListener { selectedTab = "search"; openedAddress = null; conversationSearchQuery = ""; showSearch() }
        }
        if (::topCallButton.isInitialized) topCallButton.visibility = View.GONE
    }

    private data class InboxThread(
        val address: String,
        val latest: MessageRow,
        var unread: Int,
        var matchesQuery: Boolean
    )

    private companion object {
        const val THREADS_PER_PAGE = 100
        const val ACTION_SMS_DATA_CHANGED = "com.farshekashan.smsh.SMS_DATA_CHANGED"
        const val ACTION_SMS_SENT = "com.farshekashan.smsh.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.farshekashan.smsh.SMS_DELIVERED"
        const val ACTION_SMS_STATUS_CHANGED = "com.farshekashan.smsh.SMS_STATUS_CHANGED"
        const val EXTRA_SMS_ROW_ID = "sms_row_id"
    }

    private fun showInbox(query: String? = null) {
        openedAddress = null
        selectedTab = "inbox"
        reset("پیام‌ها")
        val loadId = inboxLoadId
        val search = EditText(this).apply {
            hint = "جست‌وجو در پیام‌ها"
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(field, 28)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setOnEditorActionListener { _, _, _ -> showInbox(text.toString()); true }
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        if (threadSelectionMode) addThreadSelectionControls()
        if (!query.isNullOrBlank()) search.setText(query)

        // Returning to the inbox reuses the already-built conversation list in RAM.
        if (query.isNullOrBlank()) {
            val cachedThreads = smsThreadCache
            if (cachedThreads != null) {
                inboxThreads = cachedThreads
                displayedThreadCount = 0
                renderNextInboxPage()
                return
            }
        }

        val loading = TextView(this).apply {
            text = "در حال بارگذاری گفتگوهای اخیر…"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(48), dp(18), dp(48))
        }
        content.addView(loading)

        val worker = Thread({
            // Warm the contact cache once before search-by-contact-name matching.
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                preloadContactNames()
            }
            if (Thread.currentThread().isInterrupted) return@Thread
            val threads = readInboxThreads(query, loading, loadId)

            runOnUiThread {
                if (loadId != inboxLoadId || isFinishing || isDestroyed || selectedTab != "inbox") {
                    return@runOnUiThread
                }
                content.removeView(loading)
                if (threads.isEmpty()) {
                    val message = inboxError ?: if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
                        "دسترسی خواندن پیامک فعال نیست. مجوز پیامک را از تنظیمات برنامه فعال کنید."
                    else "پیامی در پیام‌های اخیر پیدا نشد."
                    emptyMessage(message)
                    if (inboxError != null) {
                        content.addView(actionButton("تلاش دوباره") { inboxError = null; showInbox(query) })
                    }
                    return@runOnUiThread
                }
                inboxThreads = threads
                displayedThreadCount = 0
                renderNextInboxPage()
            }
        }, "sms-inbox-loader")
        inboxWorker = worker
        worker.start()
    }

    private fun renderNextInboxPage() {
        loadMoreButton?.let { content.removeView(it) }
        loadMoreButton = null
        val end = minOf(displayedThreadCount + THREADS_PER_PAGE, inboxThreads.size)
        for (index in displayedThreadCount until end) {
            val item = inboxThreads[index]
            addThreadRow(item.address, item.latest, item.unread)
        }
        displayedThreadCount = end
        if (displayedThreadCount < inboxThreads.size) {
            val button = actionButton("نمایش گفتگوهای قدیمی‌تر (${displayedThreadCount} از ${inboxThreads.size})") {
                renderNextInboxPage()
            }
            loadMoreButton = button
            content.addView(button)
        }
    }

    /**
     * Reads the full SMS archive on a worker thread and retains only one summary
     * per conversation. UI rows are rendered in small pages to keep scrolling smooth.
     */
    private fun readInboxThreads(query: String? = null, loading: TextView? = null, loadId: Int = inboxLoadId): List<InboxThread> {
        inboxError = null
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val archive = loadSmsArchive(loading, loadId)
        if (archive == null) return emptyList()

        val needle = query?.trim()?.takeIf { it.isNotEmpty() }
        if (needle == null) smsThreadCache?.let { return it }
        val grouped = LinkedHashMap<String, InboxThread>()
        for (message in archive) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val address = message.address.ifBlank { "ناشناس" }
            val existing = grouped[address]
            if (existing == null) {
                val nameMatches = needle != null && cachedContactName(address).contains(needle, true)
                grouped[address] = InboxThread(
                    address,
                    message,
                    if (message.type == Telephony.Sms.MESSAGE_TYPE_INBOX && message.read == 0) 1 else 0,
                    needle == null || message.body.contains(needle, true) || address.contains(needle, true) || nameMatches
                )
            } else {
                if (message.type == Telephony.Sms.MESSAGE_TYPE_INBOX && message.read == 0) existing.unread++
                if (needle != null && (message.body.contains(needle, true) || address.contains(needle, true))) {
                    existing.matchesQuery = true
                }
                // The archive is DESC by date, so the first item is the newest.
            }
        }
        val result = grouped.values.filter { it.matchesQuery }.sortedByDescending { it.latest.date }
        if (needle == null) smsThreadCache = result
        return result
    }

    /** Loads every SMS exactly once per app process. Subsequent inbox/conversation visits use RAM. */
    private fun loadSmsArchive(loading: TextView?, loadId: Int): List<MessageRow>? {
        smsArchiveCache?.let { return it }
        synchronized(archiveLock) {
            smsArchiveCache?.let { return it }
            val diskCache = try { SmsStore(this).readCachedMessages() } catch (_: Exception) { emptyList() }
            if (diskCache.isNotEmpty()) {
                smsArchiveCache = diskCache
                smsByAddressCache = diskCache.groupBy { it.address }.mapValues { (_, messages) -> messages.sortedBy { it.date } }
                return diskCache
            }
            val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS)
            val result = ArrayList<MessageRow>()
            var scanned = 0
            try {
                contentResolver.query(Telephony.Sms.CONTENT_URI, projection, null, null,
                    "${Telephony.Sms.DATE} DESC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val addressCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val typeCol = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                    val readCol = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                    val statusCol = cursor.getColumnIndex(Telephony.Sms.STATUS)
                    while (cursor.moveToNext()) {
                        if (Thread.currentThread().isInterrupted) return null
                        result.add(MessageRow(cursor.getLong(idCol), cursor.getString(addressCol) ?: "ناشناس",
                            cursor.getString(bodyCol) ?: "", cursor.getLong(dateCol), false,
                            cursor.getInt(typeCol), cursor.getInt(readCol),
                            if (statusCol >= 0) cursor.getInt(statusCol) else -1))
                        scanned++
                        if (scanned % 2000 == 0 && loading != null) {
                            val count = scanned
                            runOnUiThread {
                                if (loadId == inboxLoadId && !isFinishing && !isDestroyed) {
                                    loading.text = "در حال بارگذاری همه پیام‌ها… ${String.format(Locale.getDefault(), "%,d", count)}"
                                }
                            }
                        }
                    }
                } ?: run {
                    inboxError = "سامانه پیامک گوشی پاسخی نداد. بررسی کنید SMH برنامه پیش‌فرض پیامک باشد."
                    return null
                }
                // Save the complete archive to SQLite so it survives closing the app.
                SmsStore(this).replaceCachedMessages(result)
                smsArchiveCache = result
                // Build address index once: opening a conversation becomes a direct lookup.
                smsByAddressCache = result.groupBy { it.address }.mapValues { (_, messages) -> messages.asReversed() }
                return result
            } catch (e: Exception) {
                inboxError = "خطا هنگام خواندن پیامک‌ها: ${e.localizedMessage ?: e.javaClass.simpleName}"
                return null
            }
        }
    }

    private fun invalidateSmsCache() {
        try { SmsStore(this).replaceCachedMessages(emptyList()) } catch (_: Exception) {}
        smsArchiveCache = null
        smsThreadCache = null
        smsByAddressCache = null
        inboxThreads = emptyList()
    }

    private fun removeMessageFromCache(id: Long) {
        try { SmsStore(this).deleteCachedMessage(id) } catch (_: Exception) {}
        smsArchiveCache = smsArchiveCache?.filterNot { it.id == id }
        smsThreadCache = null
        smsByAddressCache = smsArchiveCache?.groupBy { it.address }
            ?.mapValues { (_, messages) -> messages.sortedBy { it.date } }
    }

    private fun addThreadRow(address: String, latest: MessageRow, unread: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundColor(if (threadSelectionMode && address in selectedThreadAddresses) Color.rgb(220, 234, 252) else surface)
            isClickable = true
            setOnClickListener {
                if (threadSelectionMode) {
                    toggleThreadSelection(address)
                } else {
                    conversationReturnTab = selectedTab
                    messageSelectionMode = false; selectedMessageIds.clear()
                    if (openedAddress != address) conversationSearchQuery = ""
                    openedAddress = address
                    showConversation(address)
                }
            }
            setOnLongClickListener {
                if (!threadSelectionMode) {
                    threadSelectionMode = true
                    selectedThreadAddresses.clear()
                    selectedThreadAddresses.add(address)
                    showInbox()
                } else toggleThreadSelection(address)
                true
            }
        }
        val avatar = TextView(this).apply {
            text = contactName(address).take(1).uppercase(Locale.getDefault())
            textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = oval(avatarColor(address))
        }
        if (threadSelectionMode) {
            row.addView(selectionMark(address in selectedThreadAddresses), LinearLayout.LayoutParams(dp(36), dp(48)))
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val topLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topLine.addView(TextView(this@MainActivity).apply {
            text = contactName(address); textSize = 16f * messageFontScale; setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topLine.addView(TextView(this@MainActivity).apply {
            text = shortDate(latest.date); textSize = 11f; setTextColor(muted)
        })
        details.addView(topLine)
        val preview = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        preview.addView(TextView(this@MainActivity).apply {
            text = latest.body.replace("\n", " "); maxLines = 1; textSize = 13f * messageFontScale; setTextColor(muted)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (unread > 0) preview.addView(TextView(this@MainActivity).apply {
            text = unread.toString(); textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = oval(blue); minWidth = dp(22); setPadding(dp(5), dp(3), dp(5), dp(3))
        })
        details.addView(preview)
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row)
        content.addView(View(this).apply { setBackgroundColor(dividerColor) },
            LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun showConversation(address: String, targetMessageId: Long? = null) {
        openedAddress = address
        reset(contactName(address))
        // The two actions stay in the fixed app toolbar, never below the message history.
        topSearchButton.text = "⌕"
        topSearchButton.contentDescription = "جست‌وجو در پیام‌های همین مخاطب"
        topSearchButton.setOnClickListener { showConversationSearchDialog(address) }
        topCallButton.visibility = View.VISIBLE
        topCallButton.setOnClickListener { callContact(address) }
        if (smsArchiveCache == null) loadSmsArchive(null, inboxLoadId)
        conversationRows = smsByAddressCache?.get(address)
            ?: (smsArchiveCache ?: emptyList()).filter { it.address == address }.sortedBy { it.date }
        val draftPrefs = getSharedPreferences("sms_drafts", MODE_PRIVATE)
        conversationInput.removeTextChangedListener(draftWatcher)
        activeDraftAddress = address
        conversationInput.setText(draftPrefs.getString(address, "") ?: "")
        conversationInput.setSelection(conversationInput.text.length)
        conversationInput.addTextChangedListener(draftWatcher)
        // Opening a conversation marks all incoming messages in it as read in both
        // Android's SMS provider and our persistent cache, clearing the inbox badge.
        markConversationRead(address)
        if (messageSelectionMode) addMessageSelectionControls(address)
        if (conversationRows.isEmpty()) { emptyMessage("پیامی برای این گفتگو پیدا نشد."); return }
        // IMPORTANT: when a conversation search is active, derive results ONLY from
        // conversationRows (already keyed by the selected address), never from the global archive.
        val scopedMatches = if (conversationSearchQuery.isBlank()) emptyList()
            else conversationRows.filter { it.body.contains(conversationSearchQuery, ignoreCase = true) }
        val isScopedSearch = conversationSearchQuery.isNotBlank()
        if (isScopedSearch) {
            val summary = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            summary.addView(TextView(this).apply {
                text = "${scopedMatches.size} پیام در همین گفتگو پیدا شد"
                textSize = 13f; setTextColor(muted)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            summary.addView(actionButton("پاک کردن") {
                conversationSearchQuery = ""
                showConversation(address)
            })
            content.addView(summary)
            if (scopedMatches.isEmpty()) {
                emptyMessage("در پیام‌های این مخاطب، عبارت موردنظر پیدا نشد.")
                conversationComposer.visibility = View.VISIBLE
                return
            }
            scopedMatches.forEach { addMessageBubble(it) }
            conversationComposer.visibility = View.VISIBLE
            conversationInput.setText("")
            mainScroll.post { mainScroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        // Open a search hit in its chronological neighborhood, not at the newest message.
        val targetIndex = targetMessageId?.let { id -> conversationRows.indexOfFirst { it.id == id } } ?: -1
        conversationStartIndex = if (targetIndex >= 0) maxOf(0, targetIndex - 50)
            else maxOf(0, conversationRows.size - 100)
        val endIndex = if (targetIndex >= 0) minOf(conversationRows.size, conversationStartIndex + 100)
            else conversationRows.size
        olderMessagesButton = null
        val searchHits = if (!isScopedSearch && lastSearchQuery.isNotBlank() && conversationReturnTab == "search")
            searchMatchIdsByAddress[address].orEmpty() else emptyList()
        if (searchHits.isNotEmpty()) {
            val hitPosition = searchHits.indexOf(targetMessageId ?: activeSearchMessageId).let { if (it < 0) 0 else it }
            activeSearchMessageId = searchHits[hitPosition]
            val hitBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8)) }
            hitBar.addView(actionButton("↑ پیام قبلی") {
                val current = searchHits.indexOf(activeSearchMessageId).let { if (it < 0) 0 else it }
                val next = (current - 1 + searchHits.size) % searchHits.size
                activeSearchMessageId = searchHits[next]
                showConversation(address, searchHits[next])
            }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            hitBar.addView(TextView(this).apply { text = "${hitPosition + 1} از ${searchHits.size}"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(blue) }, LinearLayout.LayoutParams(0, -2, 1f))
            hitBar.addView(actionButton("پیام بعدی ↓") {
                val current = searchHits.indexOf(activeSearchMessageId).let { if (it < 0) 0 else it }
                val next = (current + 1) % searchHits.size
                activeSearchMessageId = searchHits[next]
                showConversation(address, searchHits[next])
            }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            content.addView(hitBar)
        }
        if (conversationStartIndex > 0) {
            val older = actionButton("نمایش ۱۰۰ پیام قدیمی‌تر") { loadOlderConversationMessages() }
            olderMessagesButton = older
            content.addView(older)
        }
        var targetView: View? = null
        for (index in conversationStartIndex until endIndex) {
            val bubble = addMessageBubble(conversationRows[index])
            if (targetIndex == index) targetView = bubble
        }
        conversationComposer.visibility = View.VISIBLE
        conversationInput.setText("")
        mainScroll.post {
            if (targetView != null) {
                mainScroll.smoothScrollTo(0, maxOf(0, targetView!!.top - dp(100)))
            } else {
                // Re-apply after layout; ScrollView can otherwise restore an old top position.
                mainScroll.post {
                    val child = mainScroll.getChildAt(0)
                    if (child != null) mainScroll.scrollTo(0, child.height)
                }
            }
        }
    }

    private fun updateMessageStatusInMemory(id: Long, status: Int, type: Int) {
        fun update(rows: List<MessageRow>): List<MessageRow> = rows.map { row ->
            if (row.id == id) row.copy(status = status, type = type) else row
        }

        smsArchiveCache = smsArchiveCache?.let(::update)
        smsByAddressCache = smsByAddressCache?.mapValues { (_, rows) -> update(rows) }
        conversationRows = update(conversationRows)
        smsThreadCache = smsThreadCache?.map { thread ->
            if (thread.latest.id == id) thread.copy(latest = thread.latest.copy(status = status, type = type))
            else thread
        }

        val row = conversationRows.firstOrNull { it.id == id }
            ?: smsArchiveCache?.firstOrNull { it.id == id }
        if (row != null && openedAddress == row.address) {
            showConversation(row.address)
        }
    }

    // Gregorian timestamp -> Jalali date, without an external library (minSdk 23).
    private fun jalaliDateTime(timestamp: Long): String {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val gy = calendar.get(java.util.Calendar.YEAR)
        val gm = calendar.get(java.util.Calendar.MONTH) + 1
        val gd = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val gDays = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var jy = if (gy > 1600) 979 else 0
        var year = if (gy > 1600) gy - 1600 else gy - 621
        val adjustedMonth = if (gm > 2) year + 1 else year
        var days = 365 * year + (adjustedMonth + 3) / 4 - (adjustedMonth + 99) / 100 +
            (adjustedMonth + 399) / 400 - 80 + gd + gDays[gm - 1]
        jy += 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val minute = calendar.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        return "${jy}/${jm.toString().padStart(2, '0')}/${jd.toString().padStart(2, '0')}  •  $hour:$minute"
            .map { ch -> if (ch in '0'..'9') ('۰'.code + (ch.code - '0'.code)).toChar() else ch }
            .joinToString("")
    }

    private fun markConversationRead(address: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.cancel(address.hashCode())
        } catch (_: Exception) {}
        // Update the in-memory conversation immediately; provider and SQLite writes
        // run off the UI thread so opening large conversations does not freeze.
        val updated = conversationRows.map { row ->
            if (row.type == Telephony.Sms.MESSAGE_TYPE_INBOX && row.read == 0) row.copy(read = 1) else row
        }
        conversationRows = updated
        smsByAddressCache = (smsByAddressCache ?: emptyMap()) + (address to updated)
        smsArchiveCache = smsArchiveCache?.map { row ->
            if (row.address == address && row.type == Telephony.Sms.MESSAGE_TYPE_INBOX && row.read == 0)
                row.copy(read = 1) else row
        }
        smsThreadCache = smsThreadCache?.map { thread ->
            if (thread.address == address) thread.copy(unread = 0) else thread
        }

        Thread({
            try {
                val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1) }
                contentResolver.update(Telephony.Sms.CONTENT_URI, values,
                    "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.TYPE}=? AND ${Telephony.Sms.READ}=0",
                    arrayOf(address, Telephony.Sms.MESSAGE_TYPE_INBOX.toString()))
            } catch (_: Exception) {}
            try { SmsStore(this).markConversationRead(address) } catch (_: Exception) {}
        }, "sms-mark-read").start()
    }

    private fun showConversationSearchDialog(address: String) {
        // Resolve the selected conversation first; the dialog never queries the global inbox.
        if (openedAddress != address) return
        val input = EditText(this).apply {
            hint = "جست‌وجو فقط در پیام‌های ${contactName(address)}"
            setSingleLine(true)
            textSize = 16f
            setText(conversationSearchQuery)
            setSelection(text.length)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("جست‌وجو در همین گفتگو")
            .setView(input)
            .setPositiveButton("جست‌وجو", null)
            .setNegativeButton("انصراف", null)
        if (conversationSearchQuery.isNotBlank()) {
            dialog.setNeutralButton("پاک کردن جست‌وجو") { _, _ ->
                conversationSearchQuery = ""
                showConversation(address)
            }
        }
        val shown = dialog.create()
        shown.setOnShowListener {
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isBlank()) {
                    input.error = "عبارت جست‌وجو را وارد کنید"
                    return@setOnClickListener
                }
                conversationSearchQuery = query
                activeSearchMessageId = null
                shown.dismiss()
                showConversation(address)
            }
        }
        shown.show()
    }

    private fun callContact(address: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null)))
        } catch (_: Exception) {
            toast("امکان باز کردن شماره‌گیر وجود ندارد.")
        }
    }

    private fun sendConversationMessage() {
        val address = openedAddress ?: return
        val body = conversationInput.text.toString().trim()

        if (body.isEmpty()) return

        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions()
            return
        }

        try {
            val now = System.currentTimeMillis()
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
            }

            val sentUri = contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
                ?: throw IllegalStateException("ذخیره پیام در صندوق خروجی انجام نشد")
            val rowId = sentUri.lastPathSegment?.toLongOrNull()
                ?: throw IllegalStateException("شناسه پیامک دریافت نشد")

            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)

            // ساخت Intent ارسال با Action اختصاصی برنامه
            // از ارجاع مستقیم به کلاس Receiver صرف‌نظر می‌کنیم تا وابستگی
            // به نام/نوع کلاس Receiver باعث خطای کامپایل نشود.
            val sentBroadcastIntent = Intent(ACTION_SMS_SENT)
            sentBroadcastIntent.setPackage(packageName)
            sentBroadcastIntent.putExtra(EXTRA_SMS_ROW_ID, rowId)

            val sentIntent = PendingIntent.getBroadcast(
                this@MainActivity,
                rowId.toInt(),
                sentBroadcastIntent,
                pendingFlags
            )

            // ساخت Intent تحویل با Action اختصاصی برنامه
            val deliveredBroadcastIntent = Intent(ACTION_SMS_DELIVERED)
            deliveredBroadcastIntent.setPackage(packageName)
            deliveredBroadcastIntent.putExtra(EXTRA_SMS_ROW_ID, rowId)

            val deliveredIntent = PendingIntent.getBroadcast(
                this@MainActivity,
                (rowId xor 0x5A5A5A5AL).toInt(),
                deliveredBroadcastIntent,
                pendingFlags
            )

            val sentRow = MessageRow(
                rowId,
                address,
                body,
                now,
                false,
                Telephony.Sms.MESSAGE_TYPE_OUTBOX,
                1,
                Telephony.Sms.STATUS_PENDING
            )

            SmsStore(this).upsertCachedMessage(sentRow)
            conversationRows = (conversationRows + sentRow).sortedBy { it.date }
            smsByAddressCache = (smsByAddressCache ?: emptyMap()) + (address to conversationRows)
            smsArchiveCache = ((smsArchiveCache ?: emptyList())
                .filterNot { it.id == rowId } + sentRow).sortedByDescending { it.date }

            conversationInput.setText("")
            showConversation(address)

            @Suppress("DEPRECATION")
            SmsManager.getDefault().sendTextMessage(
                address,
                null,
                body,
                sentIntent,
                deliveredIntent
            )

            toast("پیام در صف ارسال قرار گرفت.")
        } catch (e: Exception) {
            toast("ارسال ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص"}")
        }
    }

    private fun addMessageBubble(message: MessageRow, index: Int = -1, insertAt: Int = -1): View {
        val outgoing = message.type == Telephony.Sms.MESSAGE_TYPE_SENT ||
            message.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (outgoing) Gravity.START else Gravity.END
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val dateLabel = TextView(this).apply {
            text = jalaliDateTime(message.date)
            textSize = 10f * messageFontScale
            setTextColor(muted)
            setPadding(dp(5), 0, dp(5), dp(3))
        }
        wrap.addView(dateLabel)
        val bubble = TextView(this).apply {
            text = message.body
            textSize = 14f * messageFontScale
            setTextColor(ink); setPadding(dp(14), dp(10), dp(14), dp(8))
            val isSearchTarget = message.id == activeSearchMessageId && lastSearchQuery.isNotBlank()
            background = rounded(if (isSearchTarget) Color.rgb(255, 244, 204) else if (outgoing) outgoingBubble else surface, 18)
            setTextColor(if (isSearchTarget) Color.rgb(45, 38, 20) else ink)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            Linkify.addLinks(this, Linkify.PHONE_NUMBERS)
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(ink)
            setOnLongClickListener {
                if (!messageSelectionMode) {
                    messageSelectionMode = true
                    selectedMessageIds.clear()
                    selectedMessageIds.add(message.id)
                    showConversation(message.address)
                } else toggleMessageSelection(message.id, message.address)
                true
            }
            if (messageSelectionMode) {
                setOnClickListener { toggleMessageSelection(message.id, message.address) }
            }
        }
        if (messageSelectionMode) {
            wrap.addView(selectionMark(message.id in selectedMessageIds))
            bubble.setBackground(rounded(if (message.id in selectedMessageIds) Color.rgb(187, 214, 250) else if (outgoing) outgoingBubble else surface, 18))
            bubble.setTextColor(ink)
        }
        wrap.addView(bubble)
        if (outgoing) {
            val deliveryMark = TextView(this).apply {
                // A single tick means sent/pending; two ticks mean Android reports delivery complete.
                text = if (message.status == Telephony.Sms.STATUS_COMPLETE) "✓✓" else "✓"
                textSize = 11f
                setTextColor(if (message.status == Telephony.Sms.STATUS_COMPLETE) blue else muted)
                gravity = Gravity.END
                setPadding(dp(5), dp(2), dp(5), 0)
            }
            wrap.addView(deliveryMark)
        }
        if (insertAt >= 0) content.addView(wrap, insertAt) else content.addView(wrap)
        return wrap
    }

    private fun loadOlderConversationMessages() {
        val oldStart = conversationStartIndex
        if (oldStart <= 0) return
        val newStart = maxOf(0, oldStart - 100)
        val oldScrollY = mainScroll.scrollY
        olderMessagesButton?.let { content.removeView(it) }
        olderMessagesButton = null
        for (index in newStart until oldStart) addMessageBubble(conversationRows[index], index, index - newStart)
        conversationStartIndex = newStart
        if (newStart > 0) {
            val older = actionButton("نمایش ۱۰۰ پیام قدیمی‌تر") { loadOlderConversationMessages() }
            olderMessagesButton = older
            content.addView(older, oldStart - newStart)
        }
        mainScroll.post { mainScroll.scrollTo(0, oldScrollY + dp((oldStart - newStart) * 64)) }
    }

    private fun readAddressMessages(address: String): List<MessageRow> {
        if (smsArchiveCache == null) loadSmsArchive(null, inboxLoadId)
        return smsByAddressCache?.get(address)
            ?: smsArchiveCache?.filter { it.address == address }?.sortedBy { it.date }.orEmpty()
    }

    private fun showThreadActions(address: String) {
        val options = arrayOf("مسدود کردن شماره", "انتقال آخرین پیام به تبلیغات", "حذف همه پیام‌های این گفتگو", "باز کردن گفتگو")
        AlertDialog.Builder(this).setTitle(contactName(address)).setItems(options) { _, which ->
            when (which) {
                0 -> { addRule("blocked_numbers", address); toast("شماره مسدود شد") }
                1 -> {
                    val latest = smsByAddressCache?.get(address)?.lastOrNull()
                    if (latest != null) {
                        SmsStore(this).addSpam(latest.address, latest.body, latest.date)
                        deleteSmsFromDevice(latest.id)
                        removeMessageFromCache(latest.id)
                        showInbox()
                    }
                }
                2 -> confirmDeleteConversation(address)
                3 -> { conversationReturnTab = selectedTab; openedAddress = address; showConversation(address) }
            }
        }.show()
    }

    private fun showMessageActions(message: MessageRow) {
        val options = arrayOf("کپی متن پیام", "حذف پیام", "گزارش به‌عنوان تبلیغاتی", "مسدود کردن شماره فرستنده", "مسدود کردن این عبارت")
        AlertDialog.Builder(this).setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SMS", message.body))
                    toast("متن پیام کپی شد")
                }
                1 -> confirmDeleteMessage(message)
                2 -> {
                    SmsStore(this).addSpam(message.address, message.body, message.date)
                    deleteSmsFromDevice(message.id)
                    toast("پیام به تبلیغات منتقل شد")
                    removeMessageFromCache(message.id); showConversation(message.address)
                }
                3 -> { addRule("blocked_numbers", message.address); toast("شماره مسدود شد") }
                4 -> promptAddRule("blocked_texts", "مسدود کردن متن یا کلمه", "کلمه یا عبارت موردنظر")
            }
        }.show()
    }

    private fun confirmDeleteMessage(message: MessageRow) {
        AlertDialog.Builder(this).setMessage("این پیام برای همیشه حذف شود؟")
            .setNegativeButton("لغو", null).setPositiveButton("حذف") { _, _ ->
                deleteSmsFromDevice(message.id)
                removeMessageFromCache(message.id)
                showConversation(message.address)
            }.show()
    }

    private fun confirmDeleteConversation(address: String) {
        AlertDialog.Builder(this).setMessage("تمام پیام‌های این گفتگو حذف شوند؟")
            .setNegativeButton("لغو", null).setPositiveButton("حذف همه") { _, _ ->
                try {
                    contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.ADDRESS}=?", arrayOf(address))
                } catch (e: Exception) { toast("حذف گفتگو ناموفق بود: ${e.localizedMessage}") }
                invalidateSmsCache()
                openedAddress = null
                showInbox()
            }.show()
    }

    private fun selectionMark(checked: Boolean): TextView = TextView(this).apply {
        text = if (checked) "☑" else "☐"
        textSize = 25f
        gravity = Gravity.CENTER
        setTextColor(if (checked) blue else muted)
    }

    private fun addThreadSelectionControls() {
        selectionBar.removeAllViews()
        selectionBar.visibility = View.VISIBLE
        selectionBar.addView(actionButton("انتخاب همه") {
            selectedThreadAddresses.clear()
            selectedThreadAddresses.addAll(inboxThreads.map { it.address })
            showInbox()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectionBar.addView(actionButton("لغو انتخاب") {
            threadSelectionMode = false; selectedThreadAddresses.clear(); showInbox()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectionBar.addView(actionButton("حذف (${selectedThreadAddresses.size})") { deleteSelectedThreads() }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun toggleThreadSelection(address: String) {
        if (!selectedThreadAddresses.add(address)) selectedThreadAddresses.remove(address)
        showInbox()
    }

    private fun deleteSelectedThreads() {
        val addresses = selectedThreadAddresses.toList()
        if (addresses.isEmpty()) return
        AlertDialog.Builder(this).setMessage("گفتگوهای انتخاب‌شده حذف شوند؟")
            .setNegativeButton("لغو", null).setPositiveButton("حذف") { _, _ ->
                try {
                    // One provider operation instead of a slow delete for every conversation.
                    val placeholders = addresses.joinToString(",") { "?" }
                    contentResolver.delete(Telephony.Sms.CONTENT_URI,
                        "${Telephony.Sms.ADDRESS} IN ($placeholders)", addresses.toTypedArray())
                } catch (e: Exception) { toast("حذف گفتگوها ناموفق بود: ${e.localizedMessage}") }
                val prefs = getSharedPreferences("sms_drafts", MODE_PRIVATE).edit()
                addresses.forEach { prefs.remove(it) }
                prefs.apply()
                threadSelectionMode = false; selectedThreadAddresses.clear()
                invalidateSmsCache(); showInbox()
            }.show()
    }

    private fun addMessageSelectionControls(address: String) {
        selectionBar.removeAllViews()
        selectionBar.visibility = View.VISIBLE
        selectionBar.addView(actionButton("انتخاب همه") {
            selectedMessageIds.clear(); selectedMessageIds.addAll(conversationRows.map { it.id }); showConversation(address)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectionBar.addView(actionButton("لغو انتخاب") {
            messageSelectionMode = false; selectedMessageIds.clear(); showConversation(address)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectionBar.addView(actionButton("حذف (${selectedMessageIds.size})") { deleteSelectedMessages(address) }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun toggleMessageSelection(id: Long, address: String) {
        if (!selectedMessageIds.add(id)) selectedMessageIds.remove(id)
        showConversation(address)
    }

    private fun deleteSelectedMessages(address: String) {
        val ids = selectedMessageIds.toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this).setMessage("پیام‌های انتخاب‌شده حذف شوند؟")
            .setNegativeButton("لغو", null).setPositiveButton("حذف") { _, _ ->
                try {
                    val placeholders = ids.joinToString(",") { "?" }
                    contentResolver.delete(Telephony.Sms.CONTENT_URI,
                        "${Telephony.Sms._ID} IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
                } catch (e: Exception) { toast("حذف پیام‌ها ناموفق بود: ${e.localizedMessage}") }
                messageSelectionMode = false; selectedMessageIds.clear()
                invalidateSmsCache(); showConversation(address)
            }.show()
    }

    private fun deleteSmsFromDevice(id: Long) {
        try {
            val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString())
            contentResolver.delete(uri, null, null)
        } catch (e: Exception) { toast("حذف پیام ناموفق بود: ${e.localizedMessage}") }
    }

    private fun addRule(key: String, value: String) {
        val prefs = getSharedPreferences("smsh", MODE_PRIVATE)
        val values = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        values.add(value.trim())
        prefs.edit().putStringSet(key, values).apply()
    }

    private fun promptAddRule(key: String, title: String, hint: String) {
        val input = EditText(this).apply { this.hint = hint; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("مسدود کن") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    addRule(key, value)
                    toast("به فهرست مسدودها اضافه شد")
                }
            }.show()
    }

    private fun showSpam() {
        openedAddress = null
        selectedTab = "spam"
        reset("پیام‌های تبلیغاتی")
        val rows = SmsStore(this).spamRows()
        if (rows.isEmpty()) { emptyMessage("پیام تبلیغاتی ذخیره‌شده‌ای وجود ندارد."); return }
        rows.forEach { row ->
            val card = simpleCard()
            card.addView(TextView(this).apply { text = row.address; textSize = 13f; setTextColor(muted) })
            card.addView(TextView(this).apply { text = row.body; textSize = 15f; setTextColor(ink); setPadding(0, dp(7), 0, dp(7)) })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            actions.addView(actionButton("بازیابی به صندوق") {
                val restored = SmsStore(this@MainActivity).restoreSpam(this@MainActivity, row.id)
                toast(if (restored) "پیام بازیابی شد" else "بازیابی پیام انجام نشد")
                if (restored) showSpam()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(actionButton("حذف دائمی") {
                SmsStore(this@MainActivity).deleteSpam(row.id); showSpam()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(actions)
            content.addView(card)
        }
    }

    private fun showSearch() {
        selectedTab = "search"
        openedAddress = null
        reset("جست‌وجو")
        val input = EditText(this).apply {
            hint = "نام، شماره یا متن پیام"
            setSingleLine(true); textSize = 15f
            background = rounded(surface, 14)
            setPadding(dp(14), 0, dp(14), 0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(actionButton("جست‌وجو") { performMessageSearch(input.text.toString().trim()) })
        input.setOnEditorActionListener { _, _, _ -> performMessageSearch(input.text.toString().trim()); true }
    }

    private fun performMessageSearch(query: String) {
        if (query.isBlank()) { toast("عبارت جست‌وجو را وارد کنید."); return }
        lastSearchQuery = query
        reset("نتایج جست‌وجو")
        selectedTab = "search"
        val loading = TextView(this).apply {
            text = "در حال جست‌وجوی پیام‌ها…"; gravity = Gravity.CENTER
            setTextColor(muted); setPadding(dp(12), dp(28), dp(12), dp(28))
        }
        content.addView(loading)
        val worker = Thread({
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                preloadContactNames()
            }
            val archive = loadSmsArchive(loading, inboxLoadId).orEmpty()
            // Search only actual message text; contact names and phone numbers are not message hits.
            val matches = archive.filter { row -> row.body.contains(query, true) }
                .sortedByDescending { it.date }
            val hitMap = matches.groupBy { it.address }.mapValues { (_, rows) -> rows.sortedBy { it.date }.map { it.id } }
            runOnUiThread {
                if (isFinishing || isDestroyed || selectedTab != "search" || openedAddress != null) return@runOnUiThread
                content.removeView(loading)
                searchMatchIdsByAddress = hitMap
                activeSearchMessageId = null
                if (matches.isEmpty()) { emptyMessage("پیامی مطابق جست‌وجوی شما پیدا نشد."); return@runOnUiThread }
                val count = TextView(this).apply {
                    text = "${matches.size} پیام پیدا شد"; textSize = 12f; setTextColor(muted)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                content.addView(count)
                // Show one result row per contact, with its matching-message count and a preview.
                val groupedMatches = matches.groupBy { it.address }.entries.sortedByDescending { entry ->
                    entry.value.maxOfOrNull { it.date } ?: 0L
                }
                groupedMatches.forEach { entry ->
                    val address = entry.key
                    val contactHits = entry.value.sortedBy { it.date }
                    val latestHit = contactHits.maxByOrNull { it.date } ?: return@forEach
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                        setBackgroundColor(surface); isClickable = true
                        setOnClickListener {
                            conversationReturnTab = "search"
                            conversationSearchQuery = ""
                            pendingSearchMessageId = latestHit.id
                            activeSearchMessageId = latestHit.id
                            openedAddress = address
                            showConversation(address, latestHit.id)
                        }
                    }
                    item.addView(TextView(this).apply {
                        text = contactName(address) + "  •  ${contactHits.size} پیام منطبق  •  " + shortDate(latestHit.date)
                        textSize = 12f; setTextColor(blue)
                    })
                    item.addView(TextView(this).apply {
                        text = latestHit.body; textSize = 14f; setTextColor(ink)
                        maxLines = 3; setPadding(0, dp(5), 0, 0)
                    })
                    content.addView(item)
                    content.addView(View(this).apply { setBackgroundColor(dividerColor) }, LinearLayout.LayoutParams(-1, dp(1)))
                }
            }
        }, "sms-message-search")
        inboxWorker = worker
        worker.start()
    }

    private fun composeMessage(number: String? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        val to = EditText(this).apply { hint = "شماره تلفن"; setText(number ?: selectedContactNumber ?: ""); inputType = 3 }
        val body = EditText(this).apply { hint = "متن پیام"; minLines = 3; gravity = Gravity.TOP }
        box.addView(to)
        box.addView(actionButton("انتخاب از دفترچه تلفن") {
            startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), contactRequest)
        })
        box.addView(body)
        val dialog = AlertDialog.Builder(this).setTitle("پیام جدید").setView(box)
            .setNegativeButton("انصراف", null).setPositiveButton("ارسال", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dest = to.text.toString().trim()
                val text = body.text.toString()
                if (dest.isBlank() || text.isBlank()) { toast("شماره و متن پیام را وارد کنید."); return@setOnClickListener }
                try {
                    if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                        requestRuntimePermissions(); return@setOnClickListener
                    }
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault().sendTextMessage(dest, null, text, null, null)
                    toast("درخواست ارسال پیامک ثبت شد.")
                    dialog.dismiss()
                } catch (e: Exception) { toast("ارسال ناموفق بود: ${e.localizedMessage}") }
            }
        }
        dialog.show()
    }

    private fun showAppMenu() {
        val options = arrayOf(
            "پیام جدید", "بایگانی پیام‌ها", "پیام‌های تبلیغاتی و مسدودشده",
            "جست‌وجو", "تنظیمات", "تنظیمات فیلتر تبلیغات", "بارگذاری مجدد پیام‌ها"
        )
        AlertDialog.Builder(this)
            .setTitle("منوی پیام‌ها")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> composeMessage()
                    1 -> toast("نمای بایگانی در نسخه بعدی اضافه می‌شود؛ پیام‌های اصلی دست‌نخورده‌اند.")
                    2 -> showSpam()
                    3 -> { selectedTab = "search"; showSearch() }
                    4 -> showSettings()
                    5 -> showFilterSettings()
                    6 -> { invalidateSmsCache(); showInbox() }
                }
            }.show()
    }

    private fun showSettings() {
        reset("تنظیمات")
        content.setPadding(dp(8), dp(8), dp(8), dp(16))
        settingsSection("عمومی")
        settingsRow("ظاهر برنامه", "حالت روشن، تاریک یا هماهنگ با گوشی") {
            val choices = arrayOf("هماهنگ با گوشی", "روشن", "تاریک")
            val prefs = getSharedPreferences("smsh_ui", MODE_PRIVATE)
            val current = prefs.getInt("theme_mode", 0)
            AlertDialog.Builder(this).setTitle("ظاهر برنامه").setSingleChoiceItems(choices, current) { dialog, which ->
                prefs.edit().putInt("theme_mode", which).apply()
                dialog.dismiss(); recreate()
            }.setNegativeButton("انصراف", null).show()
        }
        settingsRow("اندازه متن پیام‌ها", "با نوار زیر اندازه متن پیام‌ها را تنظیم کنید") {}
        val fontScaleLabel = TextView(this).apply {
            text = "اندازه متن: ${(messageFontScale * 100).toInt()}٪"
            textSize = 13f; setTextColor(muted); setPadding(dp(16), dp(4), dp(16), 0)
        }
        content.addView(fontScaleLabel)
        val fontScaleSlider = SeekBar(this).apply {
            max = 100
            progress = (((messageFontScale - 0.75f) / 1.45f) * 100).toInt().coerceIn(0, 100)
            setPadding(dp(16), 0, dp(16), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    messageFontScale = 0.75f + (progress / 100f) * 1.45f
                    fontScaleLabel.text = "اندازه متن: ${(messageFontScale * 100).toInt()}٪"
                    getSharedPreferences("smsh_ui", MODE_PRIVATE).edit().putFloat("message_font_scale", messageFontScale).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (openedAddress != null) showConversation(openedAddress!!) else showSettings()
                }
            })
        }
        content.addView(fontScaleSlider)
        settingsRow("اعلان‌ها", "مدیریت اعلان‌های پیامک از تنظیمات اندروید") {
            try { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }
            catch (_: Exception) { toast("تنظیمات اعلان در دسترس نیست.") }
        }
        settingsSection("ویژگی‌ها")
        settingsRow("فیلتر و حذف پیام‌های تبلیغاتی", "تشخیص خودکار، شماره‌ها و عبارت‌های مسدود") { showFilterSettings() }
        settingsRow("پیام‌های تبلیغاتی و مسدودشده", "مشاهده، بازیابی یا حذف دائمی پیام‌های فیلترشده") { showSpam() }
        settingsRow("مجوزها و برنامه پیش‌فرض پیامک", "بررسی دسترسی‌های موردنیاز برای مدیریت پیامک") { requestSmsRoleIfNeeded() }
        settingsSection("درباره")
        settingsRow("درباره SMH", "نسخه 1.0") {
            AlertDialog.Builder(this).setTitle("SMH").setMessage("مدیریت پیامک با حفظ پیام‌ها و فیلتر تبلیغات.\n\nفیلتر تبلیغات و فهرست‌های مسدودشده در این بازطراحی حفظ شده‌اند.").setPositiveButton("باشه", null).show()
        }
    }

    private fun settingsSection(label: String) {
        content.addView(TextView(this).apply {
            text = label; textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(blue); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun settingsRow(label: String, summary: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(surface, 16); isClickable = true; isFocusable = true
            addView(TextView(this@MainActivity).apply { text = label; textSize = 16f; setTextColor(ink) })
            addView(TextView(this@MainActivity).apply { text = summary; textSize = 12f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
            setOnClickListener { action() }
        }
        val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(7)
        content.addView(row, lp)
    }

    private fun showFilterSettings() {
        val options = arrayOf("روش تشخیص پیام تبلیغاتی", "فعال/غیرفعال کردن تشخیص خودکار تبلیغات", "مدیریت شماره‌های مسدود", "مدیریت متن‌های مسدود", "مدیریت کلمات تبلیغاتی", "مدیریت فرستنده‌های مجاز")
        AlertDialog.Builder(this).setTitle("تنظیمات فیلتر پیامک").setItems(options) { _, which ->
            when (which) {
                0 -> AlertDialog.Builder(this).setTitle("روش تشخیص تبلیغات")
                    .setMessage("پیام‌ها با این روش‌ها بررسی می‌شوند:\n۱. شماره یا شناسه فرستنده‌ای که مسدود کرده‌اید\n۲. متن کامل یا بخشی از متن که در فهرست مسدودها گذاشته‌اید\n۳. کلمات تبلیغاتی سفارشی شما\n۴. فهرست پیش‌فرض عبارت‌های رایج تبلیغاتی (در صورت روشن بودن تشخیص خودکار)\n\nفرستنده‌های موجود در فهرست مجاز از فیلتر عبور می‌کنند. پیام‌های مسدودشده در بخش تبلیغات ذخیره می‌شوند.")
                    .setPositiveButton("متوجه شدم", null).show()
                1 -> {
                    val prefs = getSharedPreferences("smsh", MODE_PRIVATE)
                    val enabled = prefs.getBoolean("auto_spam_enabled", true)
                    AlertDialog.Builder(this).setTitle("تشخیص خودکار تبلیغات")
                        .setMessage(if (enabled) "تشخیص خودکار فعال است. غیرفعال شود؟" else "تشخیص خودکار غیرفعال است. فعال شود؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton(if (enabled) "غیرفعال کن" else "فعال کن") { _, _ ->
                            prefs.edit().putBoolean("auto_spam_enabled", !enabled).apply()
                        }.show()
                }
                2 -> manageRules("blocked_numbers", "شماره‌های مسدود", "شماره یا بخشی از شماره")
                3 -> manageRules("blocked_texts", "عبارت‌های مسدود", "عبارت موردنظر")
                4 -> manageRules("terms", "کلمات تبلیغاتی", "کلمه یا عبارت تبلیغاتی")
                5 -> manageRules("trusted", "فرستنده‌های مجاز", "شماره یا شناسه فرستنده")
            }
        }.show()
    }

    private fun manageRules(key: String, title: String, hint: String) {
        val prefs = getSharedPreferences("smsh", MODE_PRIVATE)
        val rules = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val input = EditText(this).apply { this.hint = hint; setSingleLine(true); textSize = 15f }
        list.addView(input)
        val current = TextView(this).apply {
            text = if (rules.isEmpty()) "موردی ثبت نشده است" else rules.sorted().joinToString("\n") { "• $it" }
            textSize = 13f; setTextColor(muted); setPadding(0, dp(12), 0, dp(8))
        }
        list.addView(current)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(list)
            .setNegativeButton("بستن", null).setNeutralButton("پاک کردن همه", null)
            .setPositiveButton("افزودن", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    rules.add(value)
                    prefs.edit().putStringSet(key, rules.toSet()).apply()
                    input.text.clear()
                    current.text = rules.sorted().joinToString("\n") { "• $it" }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                rules.clear(); prefs.edit().remove(key).apply(); current.text = "موردی ثبت نشده است"
            }
        }
        current.setOnLongClickListener {
            val entries = rules.sorted().toTypedArray()
            if (entries.isNotEmpty()) AlertDialog.Builder(this).setTitle("حذف کدام مورد؟").setItems(entries) { _, index ->
                rules.remove(entries[index]); prefs.edit().putStringSet(key, rules.toSet()).apply()
                current.text = if (rules.isEmpty()) "موردی ثبت نشده است" else rules.sorted().joinToString("\n") { "• $it" }
            }.show()
            true
        }
        dialog.show()
    }

    private fun normalizePhone(number: String): String = number.filter { it.isDigit() }

    private fun cachedContactName(number: String): String = synchronized(contactNameCache) {
        contactNameCache[number] ?: contactNameCache[normalizePhone(number)] ?: number
    }

    private fun preloadContactNames() {
        if (contactsCacheLoaded) return
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null)?.use { cursor ->
                val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                synchronized(contactNameCache) {
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(numberCol) ?: continue
                        val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() && !it.contains("###") } ?: continue
                        contactNameCache[number] = name
                        contactNameCache[normalizePhone(number)] = name
                    }
                }
            }
            contactsCacheLoaded = true
        } catch (_: Exception) { }
    }

    private fun contactName(number: String): String {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return number
        synchronized(contactNameCache) {
            contactNameCache[number]?.let { return it }
            contactNameCache[normalizePhone(number)]?.let { return it }
        }
        val resolved = try {
            contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use {
                    if (it.moveToFirst()) (it.getString(0)?.takeIf { name -> name.isNotBlank() && !name.contains("###") } ?: number)
                    else number
                } ?: number
        } catch (_: Exception) { number }
        synchronized(contactNameCache) {
            contactNameCache[number] = resolved
            contactNameCache[normalizePhone(number)] = resolved
        }
        return resolved
    }

    private fun shortDate(time: Long): String {
        val now = System.currentTimeMillis()
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now)) ==
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(time))
        return if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
        else SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(time))
    }

    private fun simpleCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(surface, 16)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun actionButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = rounded(blue, 24); setPadding(dp(18), dp(11), dp(18), dp(11))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) }
    }

    private fun emptyMessage(s: String) {
        content.addView(TextView(this).apply {
            text = s; textSize = 14f; setTextColor(muted); gravity = Gravity.CENTER
            setPadding(dp(18), dp(48), dp(18), dp(48)); layoutDirection = View.LAYOUT_DIRECTION_RTL
        })
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun avatarColor(value: String) = intArrayOf(0xFF1A73E8.toInt(), 0xFF188038.toInt(),
        0xFF9334E6.toInt(), 0xFFE37400.toInt(), 0xFF007B83.toInt())[kotlin.math.abs(value.hashCode()) % 5]
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
