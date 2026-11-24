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
import android.telephony.PhoneNumberUtils
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
    private var trashReturnTab = "inbox"
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
    // Prevent repeated taps from starting duplicate archive loads for the same chat.
    private var conversationArchiveLoadAddress: String? = null
    // Tracks read-state changes in RAM without copying the entire 50k+ SMS archive.
    private val locallyReadMessageIds = HashSet<Long>()
    private var conversationRows: List<MessageRow> = emptyList()
    private var conversationStartIndex = 0
    private var olderMessagesButton: View? = null
    private var searchMatchIdsByAddress: Map<String, List<Long>> = emptyMap()
    private var activeSearchMessageId: Long? = null
    private var inboxFontScale = 1.0f
    private var chatFontScale = 1.0f
    private var bottomButtonHeightDp = 54
    private var bottomBarOffsetDp = 0
    private var recipientInput: EditText? = null
    private var recipientDialog: AlertDialog? = null
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
    @Volatile private var smsRefreshInProgress = false
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
            val rawAddress = intent.getStringExtra("sms_address") ?: return
            val body = intent.getStringExtra("sms_body") ?: return
            val date = intent.getLongExtra("sms_date", System.currentTimeMillis())
            if (id <= 0L) return

            val row = MessageRow(
                id, rawAddress, body, date, false,
                Telephony.Sms.MESSAGE_TYPE_INBOX, 0
            )
            val canonicalAddress = canonicalAddressKey(rawAddress)

            // Archive sorting/grouping can involve tens of thousands of rows.
            // Never do that work in BroadcastReceiver.onReceive (the UI thread).
            Thread({
                try {
                    synchronized(archiveLock) {
                        val archive = smsArchiveCache
                        if (archive != null && archive.none { it.id == id }) {
                            smsArchiveCache = (archive + row).sortedByDescending { it.date }
                        }

                        val byAddress = smsByAddressCache
                        if (byAddress != null) {
                            val matchingKey = byAddress.keys.firstOrNull {
                                canonicalAddressKey(it) == canonicalAddress
                            } ?: canonicalAddress
                            val updated = (byAddress[matchingKey].orEmpty()
                                .filterNot { it.id == id } + row).sortedBy { it.date }
                            smsByAddressCache = byAddress + (matchingKey to updated)
                        }

                        val cachedThreads = smsThreadCache
                        if (cachedThreads != null) {
                            val existingIndex = cachedThreads.indexOfFirst {
                                canonicalAddressKey(it.address) == canonicalAddress
                            }
                            smsThreadCache = if (existingIndex < 0) {
                                (cachedThreads + InboxThread(canonicalAddress, row, 1, true))
                                    .sortedByDescending { it.latest.date }
                            } else {
                                cachedThreads.mapIndexed { index, thread ->
                                    if (index == existingIndex) thread.copy(
                                        latest = row,
                                        unread = thread.unread + 1
                                    ) else thread
                                }.sortedByDescending { it.latest.date }
                            }
                        }
                    }
                } catch (_: Exception) { }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val currentAddress = openedAddress
                    if (currentAddress != null && canonicalAddressKey(currentAddress) == canonicalAddress) {
                        // Re-render only the active conversation after background cache work.
                        showConversation(currentAddress)
                    } else if (currentAddress == null && selectedTab == "inbox") {
                        showInbox()
                    }
                }
            }, "sms-incoming-cache").start()
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
        inboxFontScale = uiPrefs.getFloat("inbox_font_scale", 1.0f).coerceIn(0.75f, 2.2f)
        chatFontScale = uiPrefs.getFloat("chat_font_scale", 1.0f).coerceIn(0.75f, 2.2f)
        bottomButtonHeightDp = uiPrefs.getInt("bottom_button_height_dp", 54).coerceIn(42, 84)
        bottomBarOffsetDp = uiPrefs.getInt("bottom_bar_offset_dp", 0).coerceIn(0, 40)
        applyThemePalette()
        window.statusBarColor = surface
        window.navigationBarColor = page
        window.decorView.systemUiVisibility = if (isDarkTheme) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildShell()
        showInbox()
        handleExternalSmsIntent(intent)
        // Let the first frame render before opening the system SMS-role chooser.
        root.post { root.postDelayed({ requestSmsRoleIfNeeded() }, 300) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalSmsIntent(intent)
    }

    /** Opens the in-app conversation when another app (for example the Phone app)
     * launches this activity with ACTION_SENDTO / sms:number. Load history first,
     * because the archive may still be loading during a cold start.
     */
    private fun handleExternalSmsIntent(source: Intent?) {
        if (source?.action != Intent.ACTION_SENDTO) return
        val uri = source.data ?: return
        val number = Uri.decode(uri.schemeSpecificPart.orEmpty())
            .substringBefore('?').trim()
        if (number.isBlank()) return

        conversationReturnTab = "inbox"
        Thread({
            loadSmsArchive(null, inboxLoadId)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    selectedTab = "inbox"
                    showConversation(number)
                }
            }
        }, "sms-external-conversation").start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Back first exits a selection mode; it must not close the app or conversation.
        if (messageSelectionMode) {
            messageSelectionMode = false
            selectedMessageIds.clear()
            openedAddress?.let { showConversation(it) } ?: showInbox()
            return
        }
        if (threadSelectionMode) {
            threadSelectionMode = false
            selectedThreadAddresses.clear()
            showInbox()
            return
        }
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
        if (selectedTab == "trash") {
            openedAddress = null
            if (trashReturnTab == "settings") showSettings() else showInbox()
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
        try { SmsStore(this).purgeExpiredTrash() } catch (_: Exception) {}
        refreshSmsReceivedWhileAway()
    }

    /**
     * The dynamic SMS broadcast receiver is intentionally unregistered while the
     * Activity is paused. Catch up only messages newer than the RAM archive when
     * the user returns, without rescanning the complete SMS database.
     */
    private fun refreshSmsReceivedWhileAway() {
        val archiveSnapshot = smsArchiveCache ?: return
        if (smsRefreshInProgress) return
        smsRefreshInProgress = true
        Thread({
            try {
                val latestDate = archiveSnapshot.maxOfOrNull { it.date } ?: return@Thread
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
                                cursor.getLong(idCol), cursor.getString(addressCol) ?: "ناشناس",
                                cursor.getString(bodyCol) ?: "", cursor.getLong(dateCol), false,
                                cursor.getInt(typeCol), cursor.getInt(readCol),
                                if (statusCol >= 0) cursor.getInt(statusCol) else -1
                            )
                            if (row.date >= latestDate && row.type == Telephony.Sms.MESSAGE_TYPE_INBOX) {
                                received.add(row)
                            }
                        }
                    } ?: return@Thread
                } catch (_: Exception) {
                    return@Thread
                }

                if (received.isEmpty()) return@Thread
                val newIds = received.mapTo(HashSet()) { it.id }
                synchronized(archiveLock) {
                    val currentArchive = smsArchiveCache ?: archiveSnapshot
                    val merged = (currentArchive.filterNot { it.id in newIds } + received)
                        .sortedByDescending { it.date }
                    smsArchiveCache = merged
                    smsByAddressCache = merged.groupBy { it.address }
                        .mapValues { (_, rows) -> rows.sortedBy { it.date } }
                    smsThreadCache = null
                }
                try {
                    val store = SmsStore(this)
                    received.forEach { store.upsertCachedMessage(it) }
                } catch (_: Exception) { }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val currentAddress = openedAddress
                    if (currentAddress != null) showConversation(currentAddress)
                    else if (selectedTab == "inbox") showInbox()
                }
            } finally {
                smsRefreshInProgress = false
            }
        }, "sms-catch-up").start()
    }

    private fun saveCurrentDraft() {
        if (!::conversationInput.isInitialized) return
        val address = activeDraftAddress ?: return
        getSharedPreferences("sms_drafts", MODE_PRIVATE).edit()
            .putString(address, conversationInput.text?.toString().orEmpty())
            .commit()
    }

    override fun onPause() {
        saveCurrentDraft()
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
                ?.use { if (it.moveToFirst()) { selectedContactNumber = it.getString(0)
                    val number = selectedContactNumber.orEmpty()
                    recipientInput?.setText(number)
                    recipientDialog?.dismiss()
                    recipientDialog = null
                    recipientInput = null
                    if (number.isNotBlank()) openConversationForNewMessage(number) } }
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
            "trash" -> showTrash()
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
            // Keep the editor itself bounded even when a draft contains many lines.
            // Extra lines must scroll inside the text field, never extend below the IME.
            setMaxHeight(dp(132))
            minHeight = dp(48)
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_NONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(field, 26)
            setTextColor(ink)
            setHintTextColor(muted)
            // Keep the insertion point visible when a long, multi-line draft grows.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    post {
                        val cursor = selectionStart
                        val textLayout = layout
                        if (cursor >= 0 && textLayout != null) {
                            // Once the draft exceeds the visible lines, let its beginning
                            // scroll upward so the newest line and cursor stay above the IME.
                            gravity = if (textLayout.lineCount > maxLines) {
                                Gravity.TOP or Gravity.RIGHT
                            } else {
                                Gravity.CENTER_VERTICAL or Gravity.RIGHT
                            }
                            val safeCursor = cursor.coerceAtMost(textLayout.text.length)
                            val line = textLayout.getLineForOffset(safeCursor)
                            // Use TextView's own cursor-reveal routine first; it accounts for
                            // font padding, compound drawables and the editor's real viewport.
                            bringPointIntoView(safeCursor)
                            // Then clamp the editor scroll to the current cursor line. This is
                            // important for long drafts (more than 9 lines): the last typed line
                            // stays visible while the first lines scroll out of the viewport.
                            val cursorTop = textLayout.getLineTop(line) + compoundPaddingTop
                            val cursorBottom = textLayout.getLineBottom(line) + compoundPaddingTop
                            val visibleTop = scrollY + compoundPaddingTop
                            val visibleBottom = scrollY + height - compoundPaddingBottom
                            when {
                                cursorBottom > visibleBottom -> scrollTo(
                                    0, maxOf(0, cursorBottom - height + compoundPaddingBottom)
                                )
                                cursorTop < visibleTop -> scrollTo(
                                    0, maxOf(0, cursorTop - compoundPaddingTop)
                                )
                            }
                        }
                    }
                }
            })
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
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottomBarOffsetDp) })

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
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(2), dp(2), dp(2))
        val icon = TextView(this@MainActivity).apply {
            text = symbol
            textSize = 21f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(blue)
            maxLines = 1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(15, 21, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
        }
        val title = TextView(this@MainActivity).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
        }
        addView(icon, LinearLayout.LayoutParams(-1, -2))
        addView(title, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(bottomButtonHeightDp), 1f)
    }

    private fun reset(title: String) {
        saveCurrentDraft()
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
            val needsContactWarmup = query.isNullOrBlank() &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                !contactsCacheLoaded
            val threads = try {
                // Do not make the first screen wait for the Contacts provider. The
                // SMS inbox can be shown immediately; names are warmed afterward.
                if (!query.isNullOrBlank() &&
                    checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    preloadContactNames()
                }
                if (Thread.currentThread().isInterrupted) return@Thread
                readInboxThreads(query, loading, loadId)
            } catch (error: Exception) {
                inboxError = "خطا هنگام آماده‌سازی فهرست پیام‌ها: ${error.localizedMessage ?: error.javaClass.simpleName}"
                emptyList()
            }

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

            // Contact lookup is non-critical for the initial inbox. Warm the cache
            // in the background and refresh labels only after the SMS list is visible.
            if (needsContactWarmup && !Thread.currentThread().isInterrupted) {
                preloadContactNames()
                if (contactsCacheLoaded) {
                    runOnUiThread {
                        if (loadId == inboxLoadId && !isFinishing && !isDestroyed &&
                            selectedTab == "inbox" && openedAddress == null) {
                            showInbox()
                        }
                    }
                }
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
        val normalizedNeedle = needle?.let { normalizeSearchText(it) }.orEmpty()
        val needleDigits = needle?.let { normalizePhone(it) }.orEmpty()
        val grouped = LinkedHashMap<String, InboxThread>()
        var processed = 0
        for (message in archive) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            processed++
            if (processed % 5000 == 0 && loading != null) {
                val count = processed
                runOnUiThread {
                    if (loadId == inboxLoadId && !isFinishing && !isDestroyed) {
                        loading.text = "در حال آماده‌سازی فهرست پیام‌ها… ${String.format(Locale.getDefault(), "%,d", count)} از ${String.format(Locale.getDefault(), "%,d", archive.size)}"
                    }
                }
            }
            val rawAddress = message.address.ifBlank { "ناشناس" }
            // Group every spelling of the same Iranian number under one local key.
            // This prevents 09..., 98..., +98... and 0098... from creating
            // separate inbox rows when the carrier/provider changes the format.
            val address = canonicalAddressKey(rawAddress)
            val existing = grouped[address]
            if (existing == null) {
                // Contact names are already preloaded in bulk. Falling back to
                // PhoneLookup here causes thousands of synchronous provider queries
                // (one per thread) on phones with large SMS archives.
                val name = cachedContactName(address)
                val nameMatches = needle != null && normalizedNeedle.isNotBlank() &&
                    normalizeSearchText(name).contains(normalizedNeedle)
                val phoneMatches = needle != null && phoneMatchesSearch(address, needle, needleDigits)
                val bodyMatches = needle == null || message.body.contains(needle, ignoreCase = true)
                grouped[address] = InboxThread(
                    address,
                    message,
                    if (message.type == Telephony.Sms.MESSAGE_TYPE_INBOX && message.read == 0 && message.id !in locallyReadMessageIds) 1 else 0,
                    bodyMatches || phoneMatches || nameMatches
                )
            } else {
                if (message.type == Telephony.Sms.MESSAGE_TYPE_INBOX && message.read == 0 && message.id !in locallyReadMessageIds) existing.unread++
                if (needle == null || message.body.contains(needle, ignoreCase = true) ||
                    phoneMatchesSearch(address, needle, needleDigits)) {
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
                smsByAddressCache = diskCache.groupBy { it.address }.mapValues { (_, messages) -> messages.asReversed() }
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
        // Kept only for explicit manual refreshes and operations that import unknown rows.
        try { SmsStore(this).replaceCachedMessages(emptyList()) } catch (_: Exception) {}
        smsArchiveCache = null
        smsThreadCache = null
        smsByAddressCache = null
        inboxThreads = emptyList()
    }

    /**
     * Apply deletions to the RAM and SQLite caches incrementally. This is deliberately
     * different from invalidateSmsCache(): deleting a few rows must never trigger a
     * complete scan of the device's SMS provider (which may contain tens of thousands
     * of messages).
     */
    private fun removeMessagesFromCache(ids: Set<Long> = emptySet(), addresses: Set<String> = emptySet()) {
        if (ids.isEmpty() && addresses.isEmpty()) return
        try {
            val store = SmsStore(this)
            store.deleteCachedMessages(ids)
            store.deleteCachedAddresses(addresses)
        } catch (_: Exception) {}

        val oldArchive = smsArchiveCache
        val affectedAddresses = LinkedHashSet<String>().apply {
            addAll(addresses)
            oldArchive?.filter { it.id in ids }?.forEach { add(it.address) }
            smsByAddressCache?.forEach { (address, rows) ->
                if (rows.any { it.id in ids }) add(address)
            }
            if (openedAddress != null && conversationRows.any { it.id in ids }) add(openedAddress!!)
        }

        if (oldArchive != null) {
            smsArchiveCache = oldArchive.filterNot { it.id in ids || it.address in addresses }
        }

        val oldByAddress = smsByAddressCache
        if (oldByAddress != null || oldArchive != null) {
            val updatedByAddress = oldByAddress?.toMutableMap() ?: mutableMapOf()
            affectedAddresses.forEach { address ->
                val sourceRows = oldByAddress?.get(address)
                    ?: oldArchive?.filter { it.address == address }.orEmpty()
                val remaining = sourceRows
                    .filterNot { it.id in ids || address in addresses }
                    .sortedBy { it.date }
                if (remaining.isEmpty()) updatedByAddress.remove(address)
                else updatedByAddress[address] = remaining
            }
            // A complete-address deletion must also remove that key even if it was not cached.
            addresses.forEach { updatedByAddress.remove(it) }
            smsByAddressCache = updatedByAddress
        }

        val oldThreads = smsThreadCache
        if (oldThreads != null) {
            val updatedThreads = oldThreads.filterNot { it.address in affectedAddresses }.toMutableList()
            affectedAddresses.forEach { address ->
                val remaining = smsByAddressCache?.get(address).orEmpty()
                if (remaining.isNotEmpty()) {
                    val latest = remaining.maxByOrNull { it.date }!!
                    val unread = remaining.count {
                        it.type == Telephony.Sms.MESSAGE_TYPE_INBOX && it.read == 0
                    }
                    updatedThreads.add(InboxThread(address, latest, unread, true))
                }
            }
            smsThreadCache = updatedThreads.sortedByDescending { it.latest.date }
        }

        openedAddress?.let { currentAddress ->
            if (currentAddress in affectedAddresses || currentAddress in addresses) {
                conversationRows = smsByAddressCache?.get(currentAddress).orEmpty()
            }
        }
        if (oldThreads != null) inboxThreads = smsThreadCache.orEmpty()
    }

    private fun removeMessageFromCache(id: Long) {
        removeMessagesFromCache(ids = setOf(id))
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
                if (threadSelectionMode) toggleThreadSelection(address)
                else showThreadActions(address)
                true
            }
        }
        val displayName = cachedContactName(address)
        val avatar = TextView(this).apply {
            text = displayName.take(1).uppercase(Locale.getDefault())
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
            text = displayName; textSize = 16f * inboxFontScale; setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topLine.addView(TextView(this@MainActivity).apply {
            text = shortDate(latest.date); textSize = 11f; setTextColor(muted)
        })
        details.addView(topLine)
        val preview = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val draftText = getSharedPreferences("sms_drafts", MODE_PRIVATE).getString(address, "")?.trim().orEmpty()
        preview.addView(TextView(this@MainActivity).apply {
            text = if (draftText.isNotEmpty()) "پیش‌نویس: ${draftText.replace("\n", " ")}" else latest.body.replace("\n", " ")
            maxLines = 1
            textSize = 13f * inboxFontScale
            setTextColor(if (draftText.isNotEmpty()) Color.rgb(220, 45, 45) else muted)
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

    private fun showConversation(address: String, targetMessageId: Long? = null, preserveScrollY: Int? = null) {
        saveCurrentDraft()
        openedAddress = address
        reset(contactName(address))
        // Contact-provider lookups can block on some devices; resolve uncached numbers in background.
        resolveConversationContactNameAsync(address)
        // The two actions stay in the fixed app toolbar, never below the message history.
        topSearchButton.text = "⌕"
        topSearchButton.contentDescription = "جست‌وجو در پیام‌های همین مخاطب"
        topSearchButton.setOnClickListener { showConversationSearchDialog(address) }
        topCallButton.visibility = View.VISIBLE
        topCallButton.setOnClickListener { callContact(address) }
        // A cold archive load can involve tens of thousands of provider rows. Never
        // perform it from this click handler: doing so freezes the UI when opening a
        // newly received message before the background inbox load has completed.
        if (smsArchiveCache == null) {
            conversationRows = emptyList()
            conversationComposer.visibility = View.GONE
            emptyMessage("در حال بارگذاری پیام‌ها…")
            if (conversationArchiveLoadAddress != address) {
                conversationArchiveLoadAddress = address
                val loadId = inboxLoadId
                Thread({
                    val archive = loadSmsArchive(null, loadId)
                    runOnUiThread {
                        if (conversationArchiveLoadAddress == address) {
                            conversationArchiveLoadAddress = null
                        }
                        if (isFinishing || isDestroyed || openedAddress != address) return@runOnUiThread
                        if (archive != null) {
                            showConversation(address)
                        } else {
                            conversationComposer.visibility = View.VISIBLE
                            emptyMessage(inboxError ?: "بارگذاری پیام‌ها انجام نشد. دوباره تلاش کنید.")
                        }
                    }
                }, "conversation-archive-loader").start()
            }
            return
        }
        // Match all stored address spellings for this recipient (e.g. +98..., 0098..., local format).
        conversationRows = readAddressMessages(address)
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
        // A new recipient may have no previous SMS history; keep the composer visible.
        conversationComposer.visibility = View.VISIBLE
        if (conversationRows.isEmpty()) { emptyMessage("هنوز پیامی در این گفتگو وجود ندارد. می‌توانید پیام جدید بنویسید."); return }
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
        mainScroll.post {
            if (preserveScrollY != null) {
                // Keep the user's current place while selection mode redraws the message rows.
                mainScroll.scrollTo(0, preserveScrollY)
            } else if (targetView != null) {
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

        // Only touch unread rows in this conversation. The previous implementation
        // copied the complete SMS archive (often 50,000+ rows) every time a chat opened.
        val unreadIds = conversationRows.asSequence()
            .filter { it.type == Telephony.Sms.MESSAGE_TYPE_INBOX && it.read == 0 }
            .map { it.id }
            .toHashSet()
        if (unreadIds.isEmpty()) return
        locallyReadMessageIds.addAll(unreadIds)

        val matching = matchingAddressKeys(address)
        conversationRows = conversationRows.map { row ->
            if (row.id in unreadIds) row.copy(read = 1) else row
        }
        // Avoid calling matchingAddressKeys() for every inbox thread: that helper
        // scans every cached address, making this update O(threadCount * addressCount)
        // on the main thread. Compare normalized keys in one pass instead.
        val canonicalAddress = canonicalAddressKey(address)
        inboxThreads = inboxThreads.map { thread ->
            if (thread.address in matching || canonicalAddressKey(thread.address) == canonicalAddress)
                thread.copy(unread = 0) else thread
        }

        val currentByAddress = smsByAddressCache
        if (currentByAddress != null) {
            val updatedByAddress = currentByAddress.toMutableMap()
            matching.forEach { key ->
                val rows = currentByAddress[key] ?: return@forEach
                if (rows.any { it.id in unreadIds }) {
                    updatedByAddress[key] = rows.map { row ->
                        if (row.id in unreadIds) row.copy(read = 1) else row
                    }
                }
            }
            smsByAddressCache = updatedByAddress
        }
        smsThreadCache = smsThreadCache?.map { thread ->
            if (thread.address in matching) thread.copy(unread = 0) else thread
        }

        // Provider and persistent-cache writes stay off the UI thread.
        Thread({
            try {
                val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1) }
                val store = SmsStore(this)
                matching.forEach { key ->
                    contentResolver.update(Telephony.Sms.CONTENT_URI, values,
                        "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.TYPE}=? AND ${Telephony.Sms.READ}=0",
                        arrayOf(key, Telephony.Sms.MESSAGE_TYPE_INBOX.toString()))
                    store.markConversationRead(key)
                }
            } catch (_: Exception) {}
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
            textSize = 10f * chatFontScale
            setTextColor(muted)
            setPadding(dp(5), 0, dp(5), dp(3))
        }
        wrap.addView(dateLabel)
        val bubble = TextView(this).apply {
            text = message.body
            textSize = 14f * chatFontScale
            setTextColor(ink); setPadding(dp(14), dp(10), dp(14), dp(8))
            val isSearchTarget = message.id == activeSearchMessageId && lastSearchQuery.isNotBlank()
            background = rounded(if (isSearchTarget) Color.rgb(255, 244, 204) else if (outgoing) outgoingBubble else surface, 18)
            setTextColor(if (isSearchTarget) Color.rgb(45, 38, 20) else ink)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            // Phone-number linkification is useful only when the body contains digits.
            // Skipping it for ordinary text avoids extra work for long conversations.
            if (message.body.any { it.isDigit() || it == '+' }) {
                Linkify.addLinks(this, Linkify.PHONE_NUMBERS)
                movementMethod = LinkMovementMethod.getInstance()
                setLinkTextColor(ink)
            }
            setOnLongClickListener {
                if (messageSelectionMode) {
                    toggleMessageSelection(message.id, message.address)
                } else {
                    showMessagePopup(this, message)
                }
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
        val byAddress = smsByAddressCache
        val keys = matchingAddressKeys(address)
        if (byAddress != null) {
            // Most conversations have one canonical address. Their cached rows are
            // already chronological, so return them directly without copying and sorting.
            if (keys.size == 1) return byAddress[keys.first()].orEmpty()
            val exact = byAddress[address]
            if (keys.size == 1 && exact != null) return exact
            return keys.asSequence().flatMap { key -> byAddress[key].orEmpty().asSequence() }
                .distinctBy { it.id }.sortedBy { it.date }.toList()
        }
        return smsArchiveCache.orEmpty().asSequence()
            .filter { row -> row.address in keys }
            .sortedBy { it.date }
            .toList()
    }

    /** Returns all stored address spellings that resolve to the same canonical number. */
    private fun matchingAddressKeys(number: String): Set<String> {
        val keys = smsByAddressCache?.keys ?: smsArchiveCache.orEmpty().map { it.address }.toSet()
        val wanted = canonicalAddressKey(number)
        val result = LinkedHashSet<String>()
        for (candidate in keys) {
            if (candidate == number || canonicalAddressKey(candidate) == wanted) result.add(candidate)
        }
        // Keep the typed address available for a brand-new conversation with no history.
        if (result.isEmpty()) result.add(number)
        return result
    }

    private fun normalizeSearchText(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک')
        .filter { it.isLetterOrDigit() }

    private fun phoneMatchesSearch(address: String, rawQuery: String, queryDigits: String): Boolean {
        if (queryDigits.length < 3) return false
        val addressDigits = normalizePhone(address)
        if (addressDigits.contains(queryDigits)) return true
        if (queryDigits.length >= 7 && addressDigits.length >= 7) {
            return PhoneNumberUtils.compare(address, rawQuery) ||
                addressDigits.takeLast(7).contains(queryDigits.takeLast(7))
        }
        return false
    }

    private fun showThreadActions(address: String) {
        val options = arrayOf(
            "مسدود کردن شماره",
            "علامت‌گذاری به‌عنوان خوانده‌نشده",
            "انتقال آخرین پیام به تبلیغات",
            "انتخاب برای حذف",
            "باز کردن گفتگو"
        )
        AlertDialog.Builder(this).setTitle(contactName(address)).setItems(options) { _, which ->
            when (which) {
                0 -> { addRule("blocked_numbers", address); toast("شماره مسدود شد") }
                1 -> {
                    val matching = matchingAddressKeys(address)
                    Thread({
                        try {
                            val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 0) }
                            matching.forEach { key ->
                                contentResolver.update(Telephony.Sms.CONTENT_URI, values,
                                    "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.TYPE}=?",
                                    arrayOf(key, Telephony.Sms.MESSAGE_TYPE_INBOX.toString()))
                            }
                        } catch (_: Exception) {}
                        runOnUiThread {
                            smsThreadCache = smsThreadCache?.map { thread ->
                                if (thread.address in matching) thread.copy(unread = thread.unread + 1) else thread
                            }
                            showInbox()
                        }
                    }, "sms-mark-unread").start()
                }
                2 -> {
                    val latest = readAddressMessages(address).lastOrNull()
                    if (latest != null) {
                        SmsStore(this).addSpam(latest.address, latest.body, latest.date)
                        deleteSmsFromDevice(latest.id)
                        removeMessageFromCache(latest.id)
                        showInbox()
                    }
                }
                3 -> {
                    // Deletion starts an explicit multi-select session; no message is deleted yet.
                    threadSelectionMode = true
                    selectedThreadAddresses.clear()
                    selectedThreadAddresses.add(address)
                    showInbox()
                }
                4 -> {
                    conversationReturnTab = selectedTab
                    openedAddress = address
                    showConversation(address)
                }
            }
        }.show()
    }

    private fun showMessagePopup(anchor: View, message: MessageRow) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("ارسال به دیگری").setOnMenuItemClickListener {
            try {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" )).apply {
                    putExtra("sms_body", message.body)
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Fallback to the platform share sheet if no SMS composer accepts smsto:.
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message.body)
                }
                startActivity(Intent.createChooser(share, "ارسال پیام با…"))
            }
            true
        }
        popup.menu.add("کپی").setOnMenuItemClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SMS", message.body))
            toast("متن پیام کپی شد")
            true
        }
        popup.menu.add("حذف").setOnMenuItemClickListener {
            confirmDeleteMessage(message)
            true
        }
        popup.show()
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
        AlertDialog.Builder(this).setMessage("این پیام به مدت ۲۴ ساعت به سطل زباله منتقل شود؟")
            .setNegativeButton("لغو", null).setPositiveButton("انتقال به سطل زباله") { _, _ ->
                val rows = readSmsRows("${Telephony.Sms._ID}=?", arrayOf(message.id.toString()))
                val saved = SmsStore(this).moveToTrash(rows, java.util.UUID.randomUUID().toString())
                if (rows.isEmpty() || saved != rows.size) {
                    toast("ذخیره پیام برای بازیابی انجام نشد؛ پیام حذف نشد.")
                    return@setPositiveButton
                }
                deleteSmsFromDevice(message.id)
                removeMessageFromCache(message.id)
                showConversation(message.address)
            }.show()
    }

    private fun confirmDeleteConversation(address: String) {
        AlertDialog.Builder(this).setMessage("تمام پیام‌های این گفتگو به مدت ۲۴ ساعت به سطل زباله منتقل شوند؟")
            .setNegativeButton("لغو", null).setPositiveButton("انتقال به سطل زباله") { _, _ ->
                val rows = readSmsRows("${Telephony.Sms.ADDRESS}=?", arrayOf(address))
                val saved = SmsStore(this).moveToTrash(rows, java.util.UUID.randomUUID().toString())
                if (rows.isEmpty() || saved != rows.size) {
                    toast("ذخیره کامل گفتگو برای بازیابی انجام نشد؛ هیچ پیامی حذف نشد.")
                    return@setPositiveButton
                }
                try {
                    contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.ADDRESS}=?", arrayOf(address))
                } catch (e: Exception) { toast("حذف گفتگو ناموفق بود: ${e.localizedMessage}"); return@setPositiveButton }
                removeMessagesFromCache(addresses = setOf(address))
                openedAddress = null
                showInbox()
            }.show()
    }

    private fun readSmsRows(selection: String, args: Array<String>): List<MessageRow> {
        val rows = ArrayList<MessageRow>()
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.STATUS)
        contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, "${Telephony.Sms.DATE} ASC")?.use { c ->
            val statusCol = c.getColumnIndex(Telephony.Sms.STATUS)
            while (c.moveToNext()) rows += MessageRow(
                c.getLong(c.getColumnIndexOrThrow(Telephony.Sms._ID)),
                c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "ناشناس",
                c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "",
                c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE)), false,
                c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.TYPE)),
                c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.READ)),
                if (statusCol >= 0) c.getInt(statusCol) else -1
            )
        } ?: throw IllegalStateException("خواندن پیام‌ها از حافظه گوشی ممکن نشد")
        return rows
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
                    // Back up every selected thread as one atomic recoverable batch first.
                    val placeholders = addresses.joinToString(",") { "?" }
                    val selection = "${Telephony.Sms.ADDRESS} IN ($placeholders)"
                    val rows = readSmsRows(selection, addresses.toTypedArray())
                    val saved = SmsStore(this).moveToTrash(rows, java.util.UUID.randomUUID().toString())
                    if (rows.isEmpty() || saved != rows.size) {
                        toast("ذخیره کامل گفتگوها برای بازیابی انجام نشد؛ هیچ گفتگویی حذف نشد.")
                        return@setPositiveButton
                    }
                    contentResolver.delete(Telephony.Sms.CONTENT_URI, selection, addresses.toTypedArray())
                } catch (e: Exception) { toast("حذف گفتگوها ناموفق بود: ${e.localizedMessage}"); return@setPositiveButton }
                val prefs = getSharedPreferences("sms_drafts", MODE_PRIVATE).edit()
                addresses.forEach { prefs.remove(it) }
                prefs.apply()
                threadSelectionMode = false; selectedThreadAddresses.clear()
                removeMessagesFromCache(addresses = addresses.toSet())
                showInbox()
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
        val previousScrollY = if (::mainScroll.isInitialized) mainScroll.scrollY else 0
        if (!selectedMessageIds.add(id)) selectedMessageIds.remove(id)
        showConversation(address, preserveScrollY = previousScrollY)
    }

    private fun deleteSelectedMessages(address: String) {
        val ids = selectedMessageIds.toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this).setMessage("پیام‌های انتخاب‌شده حذف شوند؟")
            .setNegativeButton("لغو", null).setPositiveButton("حذف") { _, _ ->
                try {
                    val placeholders = ids.joinToString(",") { "?" }
                    val args = ids.map { it.toString() }.toTypedArray()
                    val selection = "${Telephony.Sms._ID} IN ($placeholders)"
                    val rows = readSmsRows(selection, args)
                    val saved = SmsStore(this).moveToTrash(rows, java.util.UUID.randomUUID().toString())
                    if (rows.isEmpty() || saved != rows.size) {
                        toast("ذخیره پیام‌ها برای بازیابی انجام نشد؛ هیچ پیامی حذف نشد.")
                        return@setPositiveButton
                    }
                    contentResolver.delete(Telephony.Sms.CONTENT_URI, selection, args)
                } catch (e: Exception) { toast("حذف پیام‌ها ناموفق بود: ${e.localizedMessage}"); return@setPositiveButton }
                messageSelectionMode = false; selectedMessageIds.clear()
                removeMessagesFromCache(ids = ids.toSet())
                showConversation(address)
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

    private fun showTrash() {
        selectedTab = "trash"
        openedAddress = null
        val store = SmsStore(this)
        store.purgeExpiredTrash()
        reset("سطل زباله · مهلت بازیابی ۲۴ ساعت")
        val groups = store.trashGroups()
        if (groups.isEmpty()) {
            emptyMessage("سطل زباله خالی است.")
            return
        }
        groups.forEach { group ->
            val card = simpleCard()
            val name = contactName(group.address)
            card.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(ink)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            card.addView(TextView(this).apply {
                text = "${group.count} پیام · حذف‌شده در ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(group.deletedAt))}"
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(5), 0, dp(8))
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            actions.addView(actionButton("بازیابی گفتگو") {
                toast("در حال بازیابی ${group.count} پیام…")
                Thread({
                    val restored = store.restoreTrashGroup(this@MainActivity, group.batchId, group.address)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (restored > 0) {
                            invalidateSmsCache()
                            toast("$restored پیام بازیابی شد")
                        } else toast("بازیابی انجام نشد؛ مجوز برنامه پیش‌فرض پیامک را بررسی کن.")
                        showTrash()
                    }
                }, "sms-trash-restore").start()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(actionButton("حذف دائمی") {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("این ${group.count} پیام برای همیشه حذف شوند؟")
                    .setNegativeButton("لغو", null)
                    .setPositiveButton("حذف دائمی") { _, _ ->
                        store.permanentlyDeleteTrashGroup(group.batchId, group.address)
                        showTrash()
                    }.show()
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
        if (!number.isNullOrBlank()) {
            openConversationForNewMessage(number)
            return
        }

        // A fresh compose action must always start with an empty recipient field.
        selectedContactNumber = null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val to = EditText(this).apply {
            hint = "شماره تلفن"
            setText("")
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 16f
            setSingleLine(true)
        }
        recipientInput = to
        box.addView(to)
        box.addView(actionButton("انتخاب از دفترچه تلفن") {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                contactRequest
            )
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("گفتگوی جدید")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("باز کردن گفتگو", null)
            .create()
        recipientDialog = dialog
        dialog.setOnDismissListener {
            if (recipientDialog === dialog) {
                recipientDialog = null
                recipientInput = null
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dest = to.text.toString().trim()
                if (dest.isBlank()) {
                    to.error = "شماره تلفن را وارد یا از دفترچه انتخاب کنید"
                    return@setOnClickListener
                }
                selectedContactNumber = dest
                dialog.dismiss()
                recipientDialog = null
                recipientInput = null
                openConversationForNewMessage(dest)
            }
        }
        dialog.show()
    }

    /** Opens the selected recipient's existing history before the user starts composing. */
    private fun openConversationForNewMessage(number: String) {
        val address = number.trim()
        if (address.isBlank()) return
        saveCurrentDraft()
        conversationReturnTab = "inbox"
        selectedTab = "inbox"
        conversationSearchQuery = ""
        pendingSearchMessageId = null
        activeSearchMessageId = null
        messageSelectionMode = false
        selectedMessageIds.clear()
        threadSelectionMode = false
        selectedThreadAddresses.clear()

        val cached = smsArchiveCache
        if (cached != null) {
            showConversation(address)
            return
        }

        reset("در حال باز کردن گفتگو")
        emptyMessage("در حال بارگذاری سابقه پیام‌ها…")
        val loadId = inboxLoadId
        Thread({
            loadSmsArchive(null, loadId)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showConversation(address)
            }
        }, "sms-open-new-conversation").start()
    }

    private fun showAppMenu() {
        val options = arrayOf(
            "پیام جدید", "پیام‌های تبلیغاتی و مسدودشده",
            "جست‌وجو", "تنظیمات", "تنظیمات فیلتر تبلیغات", "بارگذاری مجدد پیام‌ها"
        )
        AlertDialog.Builder(this)
            .setTitle("منوی پیام‌ها")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> composeMessage()
                    1 -> showSpam()
                    2 -> { selectedTab = "search"; showSearch() }
                    3 -> showSettings()
                    4 -> showFilterSettings()
                    5 -> { invalidateSmsCache(); showInbox() }
                }
            }.show()
    }

    private fun addIntegerSetting(
        title: String, description: String, preferenceKey: String,
        currentValue: Int, minValue: Int, maxValue: Int, step: Int,
        onChanged: (Int) -> Unit
    ) {
        settingsRow(title, "$description (مقدار فعلی: $currentValue)") {
            val prefs = getSharedPreferences("smsh_ui", MODE_PRIVATE)
            val seek = SeekBar(this).apply {
                this.max = (maxValue - minValue) / step
                progress = ((currentValue - minValue) / step).coerceIn(0, this.max)
            }
            val valueLabel = TextView(this).apply {
                text = "${minValue + seek.progress * step} dp"
                textSize = 15f; setTextColor(ink); gravity = Gravity.CENTER
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
                addView(valueLabel)
                addView(seek)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueLabel.text = "${minValue + progress * step} dp"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
            AlertDialog.Builder(this).setTitle(title).setView(panel)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ذخیره") { _, _ ->
                    val value = minValue + seek.progress * step
                    prefs.edit().putInt(preferenceKey, value).apply()
                    onChanged(value)
                }.show()
        }
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
        addFontScaleSetting(
            title = "اندازه متن فهرست پیام‌ها",
            description = "اندازه نام مخاطب و پیش‌نمایش پیام در صفحه اصلی",
            initialScale = inboxFontScale,
            preferenceKey = "inbox_font_scale"
        ) { inboxFontScale = it }
        addFontScaleSetting(
            title = "اندازه متن داخل گفتگوها",
            description = "اندازه متن پیام‌ها در صفحه چت",
            initialScale = chatFontScale,
            preferenceKey = "chat_font_scale"
        ) { chatFontScale = it }
        addIntegerSetting(
            title = "ارتفاع دکمه‌های پایین صفحه",
            description = "ارتفاع هر دکمه نوار پایین بر حسب dp",
            preferenceKey = "bottom_button_height_dp",
            currentValue = bottomButtonHeightDp,
            minValue = 42,
            maxValue = 84,
            step = 2
        ) { bottomButtonHeightDp = it; buildShell(); showSettings() }
        addIntegerSetting(
            title = "فاصله نوار پایین از لبه صفحه",
            description = "میزان بالا آمدن نوار پایین از لبه صفحه بر حسب dp",
            preferenceKey = "bottom_bar_offset_dp",
            currentValue = bottomBarOffsetDp,
            minValue = 0,
            maxValue = 40,
            step = 2
        ) { bottomBarOffsetDp = it; buildShell(); showSettings() }
        settingsRow("اعلان‌ها", "مدیریت اعلان‌های پیامک از تنظیمات اندروید") {
            try { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }
            catch (_: Exception) { toast("تنظیمات اعلان در دسترس نیست.") }
        }
        settingsSection("ویژگی‌ها")
        settingsRow("سطل زباله", "بازیابی پیام‌ها و گفتگوهای حذف‌شده تا ۲۴ ساعت") {
            trashReturnTab = "settings"
            showTrash()
        }
        settingsRow("فیلتر و حذف پیام‌های تبلیغاتی", "تشخیص خودکار، شماره‌ها و عبارت‌های مسدود") { showFilterSettings() }
        settingsRow("پیام‌های تبلیغاتی و مسدودشده", "مشاهده، بازیابی یا حذف دائمی پیام‌های فیلترشده") { showSpam() }
        settingsRow("مجوزها و برنامه پیش‌فرض پیامک", "بررسی دسترسی‌های موردنیاز برای مدیریت پیامک") { requestSmsRoleIfNeeded() }
        settingsSection("درباره")
        settingsRow("درباره SMH", "نسخه 1.0") {
            AlertDialog.Builder(this).setTitle("SMH").setMessage("مدیریت پیامک با حفظ پیام‌ها و فیلتر تبلیغات.\n\nفیلتر تبلیغات و فهرست‌های مسدودشده در این بازطراحی حفظ شده‌اند.").setPositiveButton("باشه", null).show()
        }
    }

    private fun addFontScaleSetting(
        title: String,
        description: String,
        initialScale: Float,
        preferenceKey: String,
        onScaleChanged: (Float) -> Unit
    ) {
        val label = TextView(this).apply {
            text = "$title: ${(initialScale * 100).toInt()}٪"
            textSize = 14f
            setTextColor(ink)
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        content.addView(label)
        content.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(16), dp(3), dp(16), 0)
        })
        val slider = SeekBar(this).apply {
            max = 100
            progress = (((initialScale - 0.75f) / 1.45f) * 100).toInt().coerceIn(0, 100)
            setPadding(dp(16), 0, dp(16), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val scale = 0.75f + (progress / 100f) * 1.45f
                    onScaleChanged(scale)
                    label.text = "$title: ${(scale * 100).toInt()}٪"
                    getSharedPreferences("smsh_ui", MODE_PRIVATE).edit()
                        .putFloat(preferenceKey, scale).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        content.addView(slider)
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

    /** Converts Iranian international spellings to the local 0xxxxxxxxxx form. */
    private fun normalizePhone(number: String): String {
        val digits = buildString {
            number.forEach { ch ->
                when (ch) {
                    in '0'..'9' -> append(ch)
                    in '۰'..'۹' -> append(('0'.code + (ch.code - '۰'.code)).toChar())
                    in '٠'..'٩' -> append(('0'.code + (ch.code - '٠'.code)).toChar())
                }
            }
        }
        return when {
            // +98 and 98 are both reduced to digits before reaching this point.
            digits.startsWith("0098") && digits.length == 14 -> "0" + digits.substring(4)
            digits.startsWith("98") && digits.length == 12 -> "0" + digits.substring(2)
            // Also accept the national significant number without its trunk zero.
            digits.startsWith("9") && digits.length == 10 -> "0$digits"
            else -> digits
        }
    }

    /** Stable key for grouping and matching SMS threads across number formats. */
    private fun canonicalAddressKey(address: String): String {
        if (address.isBlank() || address == "ناشناس") return address
        val normalized = normalizePhone(address)
        return if (normalized.isNotBlank()) normalized else address.trim()
    }

    private fun cachedContactName(number: String): String = synchronized(contactNameCache) {
        contactNameCache[number]
            ?: contactNameCache[canonicalAddressKey(number)]
            ?: canonicalAddressKey(number)
    }

    private fun preloadContactNames() {
        if (contactsCacheLoaded) return
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val loadedNames = HashMap<String, String>()
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null)?.use { cursor ->
                val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberCol) ?: continue
                    val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() && !it.contains("###") } ?: continue
                    val key = canonicalAddressKey(number)
                    if (key.isNotBlank()) loadedNames[key] = name
                    loadedNames[number] = name
                }
            }
            synchronized(contactNameCache) { contactNameCache.putAll(loadedNames) }
            contactsCacheLoaded = true
        } catch (_: Exception) { }
    }

    /** UI-safe contact label lookup: this method never queries Android providers. */
    private fun contactName(number: String): String {
        val key = canonicalAddressKey(number)
        synchronized(contactNameCache) {
            contactNameCache[number]?.let { return it }
            contactNameCache[key]?.let { return it }
        }
        return key
    }

    /** Resolve a newly encountered number away from the main thread, then refresh its title. */
    private fun resolveConversationContactNameAsync(number: String) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val key = canonicalAddressKey(number)
        synchronized(contactNameCache) {
            if (contactNameCache.containsKey(number) || contactNameCache.containsKey(key)) return
        }
        Thread({
            val resolved = try {
                val candidates = linkedSetOf(key, number).filter { it.isNotBlank() }
                var found: String? = null
                for (candidate in candidates) {
                    contentResolver.query(
                        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(candidate)),
                        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            found = cursor.getString(0)?.takeIf { it.isNotBlank() && !it.contains("###") }
                        }
                    }
                    if (found != null) break
                }
                found ?: key
            } catch (_: Exception) { key }
            synchronized(contactNameCache) {
                contactNameCache[number] = resolved
                contactNameCache[key] = resolved
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && openedAddress == number) {
                    titleView.text = resolved
                }
            }
        }, "sms-contact-lookup").start()
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
