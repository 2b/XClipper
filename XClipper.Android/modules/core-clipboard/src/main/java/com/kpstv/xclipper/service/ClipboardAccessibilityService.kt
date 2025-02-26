package com.kpstv.xclipper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.lifecycle.MutableLiveData
import com.kpstv.core.BuildConfig
import com.kpstv.xclipper.data.helper.ClipRepositoryHelper
import com.kpstv.xclipper.data.helper.FirebaseProviderHelper
import com.kpstv.xclipper.data.provider.ClipboardProvider
import com.kpstv.xclipper.data.provider.ClipboardProviderFlags
import com.kpstv.xclipper.di.suggestions.SuggestionService
import com.kpstv.xclipper.extensions.Logger
import com.kpstv.xclipper.extensions.helper.ClipboardDetection
import com.kpstv.xclipper.extensions.helper.ClipboardLogDetector
import com.kpstv.xclipper.extensions.helper.LanguageDetector
import com.kpstv.xclipper.extensions.utils.KeyboardUtils
import com.kpstv.xclipper.ui.helpers.AppSettingKeys
import com.kpstv.xclipper.ui.helpers.AppSettings
import com.kpstv.xclipper.extensions.utils.SystemUtils
import com.kpstv.xclipper.extensions.utils.SystemUtils.isSystemOverlayEnabled
import com.kpstv.xclipper.ui.actions.SettingUIActions
import com.kpstv.xclipper.ui.activities.ChangeClipboardActivity
import dagger.hilt.android.AndroidEntryPoint
import es.dmoral.toasty.Toasty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ClipboardAccessibilityService : ServiceInterface by ServiceInterfaceImpl(), AccessibilityService() {

    @Inject
    lateinit var firebaseProviderHelper: FirebaseProviderHelper
    @Inject
    lateinit var clipboardProvider: ClipboardProvider
    @Inject
    lateinit var appSettings: AppSettings
    @Inject
    lateinit var clipboardRepositoryHelper: ClipRepositoryHelper
    @Inject
    lateinit var suggestionService: SuggestionService
    @Inject
    lateinit var settingUIActions: SettingUIActions

    private lateinit var clipboardDetector: ClipboardDetection
    private lateinit var clipboardLogDetector: ClipboardLogDetector

    /** We will save the package name to this variable from the event. */
    companion object {
        private const val EXTRA_SERVICE_TEXT = "com.kpstv.xclipper.service_text"
        private const val EXTRA_SERVICE_TEXT_LENGTH = "com.kpstv.xclipper.service_text_word_length"
        private const val EXTRA_BUBBLE_IS_EXPANDED = "com.kpstv.xclipper.bubble_is_expanded"

        private const val ACTION_INSERT_TEXT = "com.kpstv.xclipper.insert_text"
        private const val ACTION_DISABLE_SERVICE = "com.kpstv.xclipper.disable_service"
        private const val ACTION_ENABLE_IMPROVE_DETECTION = "com.kpstv.xclipper.action_enable_improve_detection"
        private const val ACTION_DISABLE_IMPROVE_DETECTION = "com.kpstv.xclipper.action_disable_improve_detection"
        private const val ACTION_GET_BUBBLE_EXPANDED_STATE = "com.kpstv.xclipper.action_get_bubble_expanded_state"

        @RequiresApi(Build.VERSION_CODES.N)
        fun disableService(context: Context) = with(context) {
            val intent = Intent(this, ClipboardAccessibilityService::class.java).apply {
                action = ACTION_DISABLE_SERVICE
            }
            startService(intent)
        }

        fun isRunning(context: Context): Boolean = SystemUtils.isAccessibilityServiceEnabled(context, ClipboardAccessibilityService::class.java)
    }

    private val keyboardVisibility: MutableLiveData<Boolean> = MutableLiveData()

    private var isBubbleExpanded: Boolean = false
    private var job = SupervisorJob()
    private fun updateKeyboardVisibilityStatus() {
        fun update(isVisible: Boolean) {
            if (keyboardVisibility.value != isVisible) keyboardVisibility.postValue(isVisible)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            job.cancel()
            job = SupervisorJob()
            CoroutineScope(Dispatchers.IO + job).launch {
                delay(500)
                if (isBubbleExpanded) return@launch
                val isVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                update(isVisible)
            }
        } else {
            update(KeyboardUtils.isKeyboardVisible(applicationContext))
        }
    }

    private lateinit var powerManager: PowerManager

    private var nodeInfo: AccessibilityNodeInfo? = null
    private var currentPackage: CharSequence? = null
    private var blackListedApps: Set<String> = emptySet()
    private var editableNode: AccessibilityNodeInfo? = null

    /**
     * TODO: Remove this unused parameter
     *
     * Indicates whether a screen is active for interaction or not.
     * If value is true -> Screen On
     *
     * This was supposed to stop all connection of Firebase when the user's
     * screen go off as a performance improvement over network.
     * There is no implementation yet but I think it's of no use since the
     * library is smart to optimize the network calls.
     */
    private val screenInteraction = MutableLiveData(true)

    private var runForNextEventAlso = false
    private val TAG = "ClipboardAccessibilityService"

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        clipboardDetector = ClipboardDetection(LanguageDetector.getCopyForLocale(applicationContext))
        clipboardLogDetector = ClipboardLogDetector.newInstanceCompat(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.packageName != packageName)
                currentPackage = event?.packageName

//             logger("$event")
//              logger("SourceText: ${event?.source}; Text is null: ${event?.text.isNullOrEmpty()}; $event")
            //   logger("Actions: ${ClipboardDetection.ignoreSourceActions(event?.source?.actionList)}, List: ${event?.source?.actionList}")
            if (event?.eventType != null)
                clipboardDetector.addEvent(event.eventType)

            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                updateKeyboardVisibilityStatus()

            val source = event?.source
            if (source != null) {
                var node: AccessibilityNodeInfo = source
                if (!node.isEditable) {
                    recursivelyFindRequiredNodeForSuggestion(node)?.let { node = it }
                }
                with(node) {
                    if (isEditable) {
                        editableNode = this
                    } else {
                        editableNode = null
                    }
                    if (textSelectionStart == textSelectionEnd && text != null) {
                        suggestionService.broadcastNodeInfo(text.toString(), textSelectionEnd)
                    }
                }
                nodeInfo = node
            }

            if (powerManager.isInteractive) {
                updateScreenInteraction(true)
            } else
                updateScreenInteraction(false)

            if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.packageName != packageName) {
                suggestionService.broadcastCloseState()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && clipboardDetector.getSupportedEventTypes(event) && !isPackageBlacklisted(event?.packageName)
            ) {
                runForNextEventAlso = true
                logger("Running for first time")
                runChangeClipboardActivity()
                return
            }

            if (runForNextEventAlso) {
                logger("Running for second time")
                runForNextEventAlso = false
                runChangeClipboardActivity()
            }
        } catch (e: Exception) {
            Logger.w(e, "Accessibility Crash")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        logger("Service Connected")
        val info = AccessibilityServiceInfo()

        info.apply {
            eventTypes =
                AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or AccessibilityEvent.TYPE_VIEW_SELECTED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 120
        }

        serviceInfo = info

        firebaseProviderHelper.observeDatabaseChangeEvents()
        clipboardProvider.observeClipboardChange(
            action = { data ->
                return@observeClipboardChange if (!isPackageBlacklisted(currentPackage)) {
                    clipboardRepositoryHelper.insertOrUpdateClip(data)
                    true
                } else false
            }
        )

        keyboardVisibility.observeForever { visible ->
            updateMemory()
            val canShowSuggestions = appSettings.canShowClipboardSuggestions()
            /** A safe check to make sure we should check permission if we
             *  are using service related to it. */
            if (isSystemOverlayEnabled(applicationContext) && canShowSuggestions && !deviceRunningLowMemory) {
                if (visible)
                    try {
                        suggestionService.start()
                    } catch (e: Exception) {
                        logger("Bubble launched failed", e)
                    }
                else
                    try {
                        suggestionService.stop()
                    } catch (e: Exception) {
                        logger("Bubble launched failed", e)
                    }
            }
        }

        registerClipboardLogDetector()
        appSettings.registerListener(settingsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            ACTION_INSERT_TEXT -> {
                val editableNode = editableNode
                val nodeInfo = nodeInfo
                if (editableNode != null)
                    actionInsertText(editableNode, intent)
                else if (nodeInfo != null)
                    actionInsertText(nodeInfo, intent)
            }
            ACTION_DISABLE_SERVICE -> @RequiresApi(Build.VERSION_CODES.N) {
                disableSelf()
                settingUIActions.refreshGeneralSettingsUI()
            }
            ACTION_ENABLE_IMPROVE_DETECTION -> {
                if (!clipboardLogDetector.isStarted()) clipboardLogDetector.startDetecting()
            }
            ACTION_DISABLE_IMPROVE_DETECTION -> {
                if (clipboardLogDetector.isStarted()) clipboardLogDetector.stopDetecting()
            }
            ACTION_GET_BUBBLE_EXPANDED_STATE -> {
                isBubbleExpanded = intent.getBooleanExtra(EXTRA_BUBBLE_IS_EXPANDED, false)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        /** Ensures that we remove database initialization observation. */
        firebaseProviderHelper.removeDatabaseInitializationObservation()
        clipboardProvider.removeClipboardObserver()
        clipboardLogDetector.dispose()
        appSettings.unregisterListener(settingsListener)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onTrimMemory(level: Int) {
        onTrimMemoryLevel(level)
    }

    private fun registerClipboardLogDetector() {
        clipboardLogDetector.registerListener(object : ClipboardLogDetector.Listener {
            override fun onClipboardEventDetected() {
                if (!runForNextEventAlso && !ChangeClipboardActivity.isRunning(applicationContext) && !isPackageBlacklisted(currentPackage)) {
                    runChangeClipboardActivity()
                }
            }
            override fun onPermissionNotGranted() {
                Toasty.error(applicationContext, "READ_LOGS Permission not granted").show()
                // TODO: Show a dialog
            }
        })
        if (appSettings.isImproveDetectionEnabled())
            clipboardLogDetector.startDetecting()
    }

    @Suppress("UNCHECKED_CAST")
    private val settingsListener = AppSettings.Listener { key, value ->
        if (key == AppSettingKeys.IMPROVE_DETECTION && value is Boolean) {
            if (value) {
                Actions.sendImproveDetectionEnable(applicationContext)
            } else {
                Actions.sendImproveDetectionDisable(applicationContext)
            }
        }
        if (key == AppSettingKeys.CLIPBOARD_BLACKLIST_APPS) {
            blackListedApps = value as Set<String>
        }
    }

    private fun actionInsertText(node: AccessibilityNodeInfo, intent: Intent) = with(node) {
        if (intent.hasExtra(EXTRA_SERVICE_TEXT)) {
            refresh()

            val pasteData = intent.getStringExtra(EXTRA_SERVICE_TEXT)

            if (isEditable) {
                val wordLength = intent.getIntExtra(EXTRA_SERVICE_TEXT_LENGTH, node.textSelectionEnd)
                actionPaste(pasteData, wordLength)
            } else {
                actionPaste(pasteData)
            }
        }
    }

    private fun AccessibilityNodeInfo.actionPaste(pasteData: String?, wordLength: Int = 0) {
        if (isEditable && text != null && textSelectionEnd == -1) { // empty EditText
            performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundleOf(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to pasteData))
            return
        }

        logger("Received ${Companion::EXTRA_SERVICE_TEXT.name}, WordLength: ${wordLength}, Text: $pasteData")

        val currentClipText = clipboardProvider.getCurrentClip().value

        clipboardProvider.setClipboard(pasteData, flag = ClipboardProviderFlags.IgnorePrimaryChangeListener)

        if (isEditable && wordLength != 0 && textSelectionEnd != -1) {
            performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, textSelectionEnd - wordLength)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textSelectionEnd)
            })
        }

        performAction(AccessibilityNodeInfo.ACTION_PASTE)

        clipboardProvider.setClipboard(currentClipText, flag = ClipboardProviderFlags.IgnorePrimaryChangeListener)
    }

    // We will find an editable node. If none of them exist it means we cannot use
    // search suggestions feature & we will directly paste the content.
    // The one which is currently focused will always be given priority.
    private tailrec fun recursivelyFindRequiredNodeForSuggestion(node: AccessibilityNodeInfo?) : AccessibilityNodeInfo? {
        if (node?.isEditable == true) {
            val editableNode = editableNode
            if (editableNode?.isEditable == true && editableNode.isFocused && editableNode.refresh()) {
                return editableNode
            }
            return node
        }
        for(i in 0 until (node?.childCount ?: 0)) {
            return recursivelyFindRequiredNodeForSuggestion(node?.getChild(i))
        }
        val editableNode = editableNode
        if (editableNode?.isEditable == true && editableNode.isFocused && editableNode.refresh()) {
            return editableNode
        }
        return null
    }

    private fun updateScreenInteraction(value: Boolean) {
        if (screenInteraction.value != value)
            screenInteraction.postValue(value)
    }

    /**
     * Returns true if the current package name is not part of blacklist app list.
     */
    private fun isPackageBlacklisted(pkg: CharSequence?) = blackListedApps.contains(pkg)

    private val lock = Any()
    private fun runChangeClipboardActivity() = synchronized(lock) {
        ChangeClipboardActivity.launch(applicationContext)
    }

    private fun logger(message: String) {
        if (BuildConfig.DEBUG)
            Log.e(TAG, message)
    }

    private fun logger(message: String, exception: Exception) {
        if (BuildConfig.DEBUG)
            Log.e(TAG, message, exception)
    }

    object Actions {
        fun sendImproveDetectionEnable(context: Context) : Unit = with(context) {
            val intent = Intent(this, ClipboardAccessibilityService::class.java).apply {
                action = ACTION_ENABLE_IMPROVE_DETECTION
            }
            startService(intent)
        }
        fun sendImproveDetectionDisable(context: Context) : Unit = with(context) {
            val intent = Intent(this, ClipboardAccessibilityService::class.java).apply {
                action = ACTION_DISABLE_IMPROVE_DETECTION
            }
            startService(intent)
        }
        fun sendClipboardInsertText(context: Context, wordLength: Int, text: String) : Unit = with(context) {
            val sendIntent = Intent(this, ClipboardAccessibilityService::class.java).apply {
                action = ACTION_INSERT_TEXT
                putExtra(EXTRA_SERVICE_TEXT_LENGTH, wordLength)
                putExtra(EXTRA_SERVICE_TEXT, text)
            }
            startService(sendIntent)
        }
        fun sendExpandedStateStatus(context: Context, isExpanded: Boolean) : Unit = with(context) {
            val intent = Intent(this, ClipboardAccessibilityService::class.java).apply {
                action = ACTION_GET_BUBBLE_EXPANDED_STATE
                putExtra(EXTRA_BUBBLE_IS_EXPANDED, isExpanded)
            }
            startService(intent)
        }
    }
}