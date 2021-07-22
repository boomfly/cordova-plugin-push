package com.adobe.phonegap.push

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources.NotFoundException
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.messaging.FirebaseMessaging
import me.leolin.shortcutbadger.ShortcutBadger
import org.apache.cordova.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.*

@Suppress("HardCodedStringLiteral")
@SuppressLint("LogConditional")
class PushPlugin : CordovaPlugin() {

  /**
   * Gets Cordova's AppCompatActivity
   *
   * @return AppCompatActivity
   */
  private val activity: AppCompatActivity
    get() = cordova.activity

  /**
   * Gets the application context from cordova's main activity.
   *
   * @return Context of the application
   */
  private val applicationContext: Context
    get() = activity.applicationContext

  /**
   * Get the NotificationManager of the activity.
   *
   * @return NotificationManager
   */
  private val notificationManager: NotificationManager
    get() = (activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

  /**
   * Return the application name
   *
   * @return String
   */
  private val appName: String
    get() = activity.packageManager.getApplicationLabel(activity.applicationInfo) as String

  /**
   * Return List of Channels
   *
   * @return JSONArray If the target API is below 26 (O), it will return an empty JSONArray
   */
  @TargetApi(26)
  @Throws(JSONException::class)
  private fun listChannels(): JSONArray {
    val channels = JSONArray()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationChannels = notificationManager.notificationChannels

      for (notificationChannel in notificationChannels) {
        val channel = JSONObject().apply {
          put(PushConstants.CHANNEL_ID, notificationChannel.id)
          put(PushConstants.CHANNEL_DESCRIPTION, notificationChannel.description)
        }

        channels.put(channel)
      }
    }

    return channels
  }

  /**
   * Deletes Notification Channel by Channel ID
   * Only called for API 26 or higher
   *
   * @param channelId
   */
  @TargetApi(26)
  private fun deleteChannel(channelId: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      notificationManager.deleteNotificationChannel(channelId)
    }
  }

  /**
   * Creates Channel
   * Only called for API 26 or higher
   *
   * @param channel
   */
  @TargetApi(26)
  @Throws(JSONException::class)
  private fun createChannel(channel: JSONObject?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      channel?.let {
        NotificationChannel(
          it.getString(PushConstants.CHANNEL_ID),
          it.optString(PushConstants.CHANNEL_DESCRIPTION, appName),
          it.optInt(PushConstants.CHANNEL_IMPORTANCE, NotificationManager.IMPORTANCE_DEFAULT)
        ).apply {
          /**
           * Enable Lights when Light Color is set.
           */
          val mLightColor = it.optInt(PushConstants.CHANNEL_LIGHT_COLOR, -1)
          if (mLightColor != -1) {
            enableLights(true)
            lightColor = mLightColor
          }

          /**
           * Set Lock Screen Visibility.
           */
          lockscreenVisibility = channel.optInt(
            PushConstants.VISIBILITY,
            NotificationCompat.VISIBILITY_PUBLIC
          )

          /**
           * Set if badge should be shown
           */
          setShowBadge(it.optBoolean(PushConstants.BADGE, true))

          /**
           * Sound Settings
           */
          val (soundUri, audioAttributes) = getNotificationChannelSound(it)
          setSound(soundUri, audioAttributes)

          /**
           * Set vibration settings.
           * Data can be either JSONArray or Boolean value.
           */
          val (hasVibration, vibrationPatternArray) = getNotificationChannelVibration(it)
          if (vibrationPatternArray != null) {
            vibrationPattern = vibrationPatternArray
          } else {
            enableVibration(hasVibration)
          }

          notificationManager.createNotificationChannel(this)
        }
      }
    }
  }

  /**
   * Get the Notification Channel Sound Data from Channel Settings
   *
   * @param channelData
   *
   * @return Pair<Uri?, AudioAttributes?>
   */
  private fun getNotificationChannelSound(channelData: JSONObject): Pair<Uri?, AudioAttributes?> {
    val audioAttributes = AudioAttributes.Builder()
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
      .build()

    val sound = channelData.optString(PushConstants.SOUND, PushConstants.SOUND_DEFAULT)

    return when {
      sound == PushConstants.SOUND_RINGTONE -> Pair(
        Settings.System.DEFAULT_RINGTONE_URI,
        audioAttributes
      )

      // Disable sound for this notification channel if an empty string is passed.
      // https://stackoverflow.com/a/47144981/6194193
      sound.isEmpty() -> Pair(null, null)

      // E.g. android.resource://org.apache.cordova/raw/<SOUND>
      sound != PushConstants.SOUND_DEFAULT -> {
        val scheme = ContentResolver.SCHEME_ANDROID_RESOURCE
        val packageName = applicationContext.packageName

        Pair(
          Uri.parse("${scheme}://$packageName/raw/$sound"),
          audioAttributes
        )
      }

      else -> Pair(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes)
    }
  }

  /**
   * Get the Notification Channel Vibration Data from Channel Settings
   *
   * @param channelData
   *
   * @return Pair<Boolean, LongArray?>
   */
  private fun getNotificationChannelVibration(channelData: JSONObject): Pair<Boolean, LongArray?> {
    var patternArray: LongArray? = null
    val mVibrationPattern = channelData.optJSONArray(PushConstants.CHANNEL_VIBRATION)

    if (mVibrationPattern != null) {
      val patternLength = mVibrationPattern.length()
      patternArray = LongArray(patternLength)

      for (i in 0 until patternLength) {
        patternArray[i] = mVibrationPattern.optLong(i)
      }
    }

    return Pair(
      channelData.optBoolean(PushConstants.CHANNEL_VIBRATION, true),
      patternArray
    )
  }

  /**
   * Creates Default Notification Channel if Needed
   * Only called for API 26 or higher
   *
   * @param options
   */
  @TargetApi(26)
  private fun createDefaultNotificationChannelIfNeeded(options: JSONObject?) {
    // only call on Android O and above
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      var id: String

      val channels = notificationManager.notificationChannels
      for (i in channels.indices) {
        id = channels[i].id
        if (id == PushConstants.DEFAULT_CHANNEL_ID) {
          return
        }
      }
      try {
        options!!.put(PushConstants.CHANNEL_ID, PushConstants.DEFAULT_CHANNEL_ID)
        options.putOpt(PushConstants.CHANNEL_DESCRIPTION, appName)
        createChannel(options)
      } catch (e: JSONException) {
        Log.e(TAG, "execute: Got JSON Exception " + e.message)
      }
    }
  }

  override fun execute(
    action: String,
    data: JSONArray,
    callbackContext: CallbackContext
  ): Boolean {
    Log.v(TAG, "Execute: Action = $action")

    gWebView = webView

    when (action) {
      PushConstants.INITIALIZE -> executeActionInitialize(data, callbackContext)
      PushConstants.UNREGISTER -> executeActionUnregister(data, callbackContext)
      PushConstants.FINISH -> callbackContext.success()
      PushConstants.HAS_PERMISSION -> executeActionHasPermission(data, callbackContext)

      PushConstants.SET_APPLICATION_ICON_BADGE_NUMBER -> {
        cordova.threadPool.execute {
          Log.v(TAG, "setApplicationIconBadgeNumber: data=$data")
          try {
            setApplicationIconBadgeNumber(
              applicationContext,
              data.getJSONObject(0).getInt(PushConstants.BADGE)
            )
          } catch (e: JSONException) {
            callbackContext.error(e.message)
          }
          callbackContext.success()
        }
      }
      PushConstants.GET_APPLICATION_ICON_BADGE_NUMBER -> {
        cordova.threadPool.execute {
          Log.v(TAG, "getApplicationIconBadgeNumber")
          callbackContext.success(
            getApplicationIconBadgeNumber(
              applicationContext
            )
          )
        }
      }
      PushConstants.CLEAR_ALL_NOTIFICATIONS -> {
        cordova.threadPool.execute {
          Log.v(TAG, "clearAllNotifications")
          clearAllNotifications()
          callbackContext.success()
        }
      }
      PushConstants.SUBSCRIBE -> {
        // Subscribing for a topic
        cordova.threadPool.execute {
          try {
            val topic = data.getString(0)
            subscribeToTopic(topic, registration_id)
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.message)
          }
        }
      }
      PushConstants.UNSUBSCRIBE -> {
        // un-subscribing for a topic
        cordova.threadPool.execute {
          try {
            val topic = data.getString(0)
            unsubscribeFromTopic(topic, registration_id)
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.message)
          }
        }
      }
      PushConstants.CREATE_CHANNEL -> {
        // un-subscribing for a topic
        cordova.threadPool.execute {
          try {
            // call create channel
            createChannel(data.getJSONObject(0))
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.message)
          }
        }
      }
      PushConstants.DELETE_CHANNEL -> {
        // un-subscribing for a topic
        cordova.threadPool.execute {
          try {
            val channelId = data.getString(0)
            deleteChannel(channelId)
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.message)
          }
        }
      }
      PushConstants.LIST_CHANNELS -> {
        // un-subscribing for a topic
        cordova.threadPool.execute {
          try {
            callbackContext.success(listChannels())
          } catch (e: JSONException) {
            callbackContext.error(e.message)
          }
        }
      }
      PushConstants.CLEAR_NOTIFICATION -> {
        // clearing a single notification
        cordova.threadPool.execute {
          try {
            Log.v(TAG, "clearNotification")
            val id = data.getInt(0)
            clearNotification(id)
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.message)
          }
        }
      }
      else -> {
        Log.e(TAG, "Invalid action : $action")
        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.INVALID_ACTION))
        return false
      }
    }
    return true
  }

  private fun executeActionInitialize(
    data: JSONArray,
    callbackContext: CallbackContext
  ) {
    // Better Logging
    fun formatLogMessage(msg: String): String = "Execute Initialize: ($msg)"

    cordova.threadPool.execute(Runnable {
      Log.v(TAG, formatLogMessage("Data=$data"))

      pushContext = callbackContext

      val sharedPref = applicationContext.getSharedPreferences(
        PushConstants.COM_ADOBE_PHONEGAP_PUSH,
        Context.MODE_PRIVATE
      )
      var jo: JSONObject? = null
      var token: String? = null
      var senderID: String? = null

      try {
        jo = data.getJSONObject(0).getJSONObject(PushConstants.ANDROID)
        senderID = getStringResourceByName(PushConstants.GCM_DEFAULT_SENDER_ID)

        // If no NotificationChannels exist create the default one
        createDefaultNotificationChannelIfNeeded(jo)

        Log.v(TAG, formatLogMessage("JSONObject=$jo"))
        Log.v(TAG, formatLogMessage("senderID=$senderID"))

        try {
          token = FirebaseInstanceId.getInstance().token
        } catch (e: IllegalStateException) {
          Log.e(TAG, formatLogMessage("Firebase Token Exception ${e.message}"))
        }

        if (token == null) {
          try {
            token = FirebaseInstanceId.getInstance().getToken(senderID, PushConstants.FCM)
          } catch (e: IllegalStateException) {
            Log.e(TAG, formatLogMessage("Firebase Token Exception ${e.message}"))
          }
        }

        if (token != "") {
          val registration = JSONObject().put(PushConstants.REGISTRATION_ID, token).apply {
            put(PushConstants.REGISTRATION_TYPE, PushConstants.FCM)
          }

          Log.v(TAG, formatLogMessage("onRegistered=$registration"))

          val topics = jo.optJSONArray(PushConstants.TOPICS)
          subscribeToTopics(topics, registration_id)

          sendEvent(registration)
        } else {
          callbackContext.error("Empty registration ID received from FCM")
          return@Runnable
        }
      } catch (e: JSONException) {
        Log.e(TAG, formatLogMessage("JSON Exception ${e.message}"))
        callbackContext.error(e.message)
      } catch (e: IOException) {
        Log.e(TAG, formatLogMessage("IO Exception ${e.message}"))
        callbackContext.error(e.message)
      } catch (e: NotFoundException) {
        Log.e(TAG, formatLogMessage("Resources NotFoundException Exception ${e.message}"))
        callbackContext.error(e.message)
      }

      jo?.let {
        /**
         * Add Shared Preferences
         *
         * Make sure to remove the preferences in the Remove step.
         */
        sharedPref.edit()?.apply {
          /**
           * Set Icon
           */
          try {
            putString(PushConstants.ICON, it.getString(PushConstants.ICON))
          } catch (e: JSONException) {
            Log.d(TAG, formatLogMessage("No Icon Options"))
          }

          /**
           * Set Icon Color
           */
          try {
            putString(PushConstants.ICON_COLOR, it.getString(PushConstants.ICON_COLOR))
          } catch (e: JSONException) {
            Log.d(TAG, formatLogMessage("No Icon Color Options"))
          }

          /**
           * Clear badge count when true
           */
          val clearBadge = it.optBoolean(PushConstants.CLEAR_BADGE, false)
          putBoolean(PushConstants.CLEAR_BADGE, clearBadge)

          if (clearBadge) {
            setApplicationIconBadgeNumber(applicationContext, 0)
          }

          /**
           * Set Sound
           */
          putBoolean(PushConstants.SOUND, it.optBoolean(PushConstants.SOUND, true))

          /**
           * Set Vibrate
           */
          putBoolean(PushConstants.VIBRATE, it.optBoolean(PushConstants.VIBRATE, true))

          /**
           * Set Clear Notifications
           */
          putBoolean(
            PushConstants.CLEAR_NOTIFICATIONS,
            it.optBoolean(PushConstants.CLEAR_NOTIFICATIONS, true)
          )

          /**
           * Set Force Show
           */
          putBoolean(
            PushConstants.FORCE_SHOW,
            it.optBoolean(PushConstants.FORCE_SHOW, false)
          )

          /**
           * Set SenderID
           */
          putString(PushConstants.SENDER_ID, senderID)

          /**
           * Set Message Key
           */
          putString(PushConstants.MESSAGE_KEY, it.optString(PushConstants.MESSAGE_KEY))

          /**
           * Set Title Key
           */
          putString(PushConstants.TITLE_KEY, it.optString(PushConstants.TITLE_KEY))

          commit()
        }
      }

      if (gCachedExtras.isNotEmpty()) {
        Log.v(TAG, formatLogMessage("Sending Cached Extras"))

        synchronized(gCachedExtras) {
          val gCachedExtrasIterator: Iterator<Bundle> = gCachedExtras.iterator()

          while (gCachedExtrasIterator.hasNext()) {
            sendExtras(gCachedExtrasIterator.next())
          }
        }

        gCachedExtras.clear()
      }
    })
  }

  private fun executeActionUnregister(
    data: JSONArray,
    callbackContext: CallbackContext
  ) {
    // Better Logging
    fun formatLogMessage(msg: String): String = "Execute Unregister: ($msg)"

    cordova.threadPool.execute {
      try {
        val sharedPref = applicationContext.getSharedPreferences(
          PushConstants.COM_ADOBE_PHONEGAP_PUSH,
          Context.MODE_PRIVATE
        )
        val topics = data.optJSONArray(0)

        if (topics != null && registration_id != "") {
          unsubscribeFromTopics(topics, registration_id)
        } else {
          FirebaseInstanceId.getInstance().deleteInstanceId()
          Log.v(TAG, formatLogMessage("UNREGISTER"))

          /**
           * Remove Shared Preferences
           *
           * Make sure to remove what was in the Initialize step.
           */
          sharedPref.edit()?.apply {
            remove(PushConstants.ICON)
            remove(PushConstants.ICON_COLOR)
            remove(PushConstants.CLEAR_BADGE)
            remove(PushConstants.SOUND)
            remove(PushConstants.VIBRATE)
            remove(PushConstants.CLEAR_NOTIFICATIONS)
            remove(PushConstants.FORCE_SHOW)
            remove(PushConstants.SENDER_ID)
            remove(PushConstants.MESSAGE_KEY)
            remove(PushConstants.TITLE_KEY)

            commit()
          }
        }

        callbackContext.success()
      } catch (e: IOException) {
        Log.e(TAG, formatLogMessage("IO Exception ${e.message}"))
        callbackContext.error(e.message)
      }
    }
  }

  private fun executeActionHasPermission(
    data: JSONArray,
    callbackContext: CallbackContext
  ) {
    // Better Logging
    fun formatLogMessage(msg: String): String = "Execute Unregister: ($msg)"

    cordova.threadPool.execute {
      try {
        val isNotificationEnabled = NotificationManagerCompat.from(applicationContext)
          .areNotificationsEnabled()

        Log.d(TAG, formatLogMessage("Has Notification Permission: $isNotificationEnabled"))

        val jo = JSONObject().apply {
          put(PushConstants.IS_ENABLED, isNotificationEnabled)
        }

        val pluginResult = PluginResult(PluginResult.Status.OK, jo).apply {
          keepCallback = true
        }

        callbackContext.sendPluginResult(pluginResult)
      } catch (e: UnknownError) {
        callbackContext.error(e.message)
      } catch (e: JSONException) {
        callbackContext.error(e.message)
      }
    }
  }

  private fun executeActionSetApplicationIconBadgeNumber() {

  }

  private fun executeActionGetApplicationIconBadgeNumber() {

  }

  private fun executeActionClearAllNotifications() {

  }

  private fun executeActionSubscribe() {

  }

  private fun executeActionUnsubscribe() {

  }

  private fun executeActionCreateChannel() {

  }

  private fun executeActionDeleteChannel() {

  }

  private fun executeActionListChannels() {

  }

  private fun executeActionClearNotification() {

  }

  /**
   *
   */
  override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
    super.initialize(cordova, webView)
    isInForeground = true
  }

  /**
   *
   */
  override fun onPause(multitasking: Boolean) {
    super.onPause(multitasking)
    isInForeground = false
  }

  /**
   *
   */
  override fun onResume(multitasking: Boolean) {
    super.onResume(multitasking)
    isInForeground = true
  }

  /**
   *
   */
  override fun onDestroy() {
    super.onDestroy()
    isInForeground = false
    gWebView = null
    val prefs = applicationContext.getSharedPreferences(
      PushConstants.COM_ADOBE_PHONEGAP_PUSH,
      Context.MODE_PRIVATE
    )
    if (prefs.getBoolean(PushConstants.CLEAR_NOTIFICATIONS, true)) {
      clearAllNotifications()
    }
  }

  private fun clearAllNotifications() {
    notificationManager.cancelAll()
  }

  private fun clearNotification(id: Int) {
    notificationManager.cancel(appName, id)
  }

  private fun subscribeToTopics(topics: JSONArray?, registrationToken: String) {
    if (topics != null) {
      var topic: String? = null
      for (i in 0 until topics.length()) {
        topic = topics.optString(i, null)
        subscribeToTopic(topic, registrationToken)
      }
    }
  }

  private fun subscribeToTopic(topic: String?, registrationToken: String) {
    if (topic != null) {
      Log.d(TAG, "Subscribing to topic: $topic")
      FirebaseMessaging.getInstance().subscribeToTopic(topic)
    }
  }

  private fun unsubscribeFromTopics(topics: JSONArray?, registrationToken: String) {
    if (topics != null) {
      var topic: String? = null
      for (i in 0 until topics.length()) {
        topic = topics.optString(i, null)
        unsubscribeFromTopic(topic, registrationToken)
      }
    }
  }

  private fun unsubscribeFromTopic(topic: String?, registrationToken: String) {
    if (topic != null) {
      Log.d(TAG, "Unsubscribing to topic: $topic")
      FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
    }
  }

  private fun getStringResourceByName(aString: String): String {
    val activity: Activity = activity
    val packageName = activity.packageName
    val resId = activity.resources.getIdentifier(aString, "string", packageName)
    return activity.getString(resId)
  }

  companion object {
    private const val TAG: String = "Push_PushPlugin"

    var isInForeground: Boolean = false
    private var pushContext: CallbackContext? = null
    private var gWebView: CordovaWebView? = null
    private val gCachedExtras = Collections.synchronizedList(ArrayList<Bundle>())

    private var registration_id = ""

    /**
     *
     */
    fun sendEvent(_json: JSONObject?) {
      val pluginResult = PluginResult(PluginResult.Status.OK, _json)
      pluginResult.keepCallback = true
      if (pushContext != null) {
        pushContext!!.sendPluginResult(pluginResult)
      }
    }

    /**
     * @todo delete?
     */
    fun sendError(message: String?) {
      val pluginResult = PluginResult(PluginResult.Status.ERROR, message)
      pluginResult.keepCallback = true
      if (pushContext != null) {
        pushContext!!.sendPluginResult(pluginResult)
      }
    }

    /**
     * Sends the pushbundle extras to the client application. If the client
     * application isn't currently active and the no-cache flag is not set, it is
     * cached for later processing.
     *
     * @param extras
     */
    @JvmStatic
    fun sendExtras(extras: Bundle?) {
      extras?.let {
        val noCache = it.getString(PushConstants.NO_CACHE)

        if (gWebView != null) {
          sendEvent(convertBundleToJson(extras))
        } else if (noCache != "1") {
          Log.v(TAG, "sendExtras: Caching extras to send at a later time.")
          gCachedExtras.add(extras)
        }
      }
    }

    /**
     * Retrives badge count from SharedPreferences
     *
     * @param context
     *
     * @return Int
     */
    fun getApplicationIconBadgeNumber(context: Context): Int {
      val settings = context.getSharedPreferences(PushConstants.BADGE, Context.MODE_PRIVATE)
      return settings.getInt(PushConstants.BADGE, 0)
    }

    /**
     * Sets badge count on application icon and in SharedPreferences
     *
     * @param context
     * @param badgeCount
     */
    @JvmStatic
    fun setApplicationIconBadgeNumber(context: Context, badgeCount: Int) {
      if (badgeCount > 0) {
        ShortcutBadger.applyCount(context, badgeCount)
      } else {
        ShortcutBadger.removeCount(context)
      }
      val editor = context.getSharedPreferences(PushConstants.BADGE, Context.MODE_PRIVATE)
        .edit()
      editor.putInt(PushConstants.BADGE, Math.max(badgeCount, 0))
      editor.apply()
    }

    /*
   *
   */
    /**
     * Serializes a bundle to JSON.
     *
     * @param extras
     *
     * @return JSONObject|null
     */
    private fun convertBundleToJson(extras: Bundle): JSONObject? {
      Log.d(TAG, "convert extras to json")
      try {
        val json = JSONObject()
        val additionalData = JSONObject()

        // Add any keys that need to be in top level json to this set
        val jsonKeySet: HashSet<String?> = HashSet<String?>()
        Collections.addAll(
          jsonKeySet,
          PushConstants.TITLE,
          PushConstants.MESSAGE,
          PushConstants.COUNT,
          PushConstants.SOUND,
          PushConstants.IMAGE
        )
        val it: Iterator<String> = extras.keySet().iterator()
        while (it.hasNext()) {
          val key = it.next()
          val value = extras[key]
          Log.d(TAG, "key = $key")
          if (jsonKeySet.contains(key)) {
            json.put(key, value)
          } else if (key == PushConstants.COLDSTART) {
            additionalData.put(key, extras.getBoolean(PushConstants.COLDSTART))
          } else if (key == PushConstants.FOREGROUND) {
            additionalData.put(key, extras.getBoolean(PushConstants.FOREGROUND))
          } else if (key == PushConstants.DISMISSED) {
            additionalData.put(key, extras.getBoolean(PushConstants.DISMISSED))
          } else if (value is String) {
            val strValue = value
            try {
              // Try to figure out if the value is another JSON object
              if (strValue.startsWith("{")) {
                additionalData.put(key, JSONObject(strValue))
              } else if (strValue.startsWith("[")) {
                additionalData.put(key, JSONArray(strValue))
              } else {
                additionalData.put(key, value)
              }
            } catch (e: Exception) {
              additionalData.put(key, value)
            }
          }
        } // while
        json.put(PushConstants.ADDITIONAL_DATA, additionalData)
        Log.v(TAG, "extrasToJSON: $json")
        return json
      } catch (e: JSONException) {
        Log.e(TAG, "extrasToJSON: JSON exception")
      }
      return null
    }

    /**
     * @return Boolean Active is true when the Cordova WebView is present.
     */
    val isActive: Boolean
      get() = gWebView != null

    /**
     * @todo delete?
     */
    protected fun setRegistrationID(token: String) {
      registration_id = token
    }
  }
}