package com.example.poddle_nfc

import android.R.attr.tag
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.*
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.experimental.and


/** PoddleNfcPlugin */
@RequiresApi(Build.VERSION_CODES.KITKAT)
class PoddleNfcPlugin : FlutterPlugin, MethodCallHandler, PluginRegistry.NewIntentListener, NfcAdapter.ReaderCallback, EventChannel.StreamHandler, ActivityAware {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var tagChannel: EventChannel
    private val NORMAL_READER_MODE = "normal"
    private val DISPATCH_READER_MODE = "dispatch"
    private val DEFAULT_READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
    private val LOG_TAG = "NfcInFlutterPlugin"

    private var activity: Activity? = null
    private var adapter: NfcAdapter? = null
    private var events: EventSink? = null

    private var currentReaderMode: String? = null
    private var lastTag: Tag? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "poddle_nfc")
        tagChannel = EventChannel(flutterPluginBinding.binaryMessenger, "poddle_nfc/tags")
        channel.setMethodCallHandler(this)
        tagChannel.setStreamHandler(this)

    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
//    if (call.method == "getPlatformVersion") {
//      result.success("Android ${android.os.Build.VERSION.RELEASE}")
//    } else {
//      result.notImplemented()
//    }
        when (call.method) {
            "getPlatformVersion" -> result.success(nfcIsEnabled())
            "startNDEFReading" -> {
                if (call.arguments !is HashMap<*, *>) {
                    result.error("MissingArguments", "startNDEFReading was called with no arguments", "")
                    return
                }
                val args: HashMap<*, *> = call.arguments as HashMap<*, *>
                val readerMode = args["reader_mode"]
                if (readerMode == null) {
                    result.error("MissingReaderMode", "startNDEFReading was called without a reader mode", "")
                    return
                }
                if (currentReaderMode != null && readerMode != currentReaderMode) {
                    // Throw error if the user tries to start reading with another reading mode
                    // than the one currently active
                    result.error("NFCMultipleReaderModes", "multiple reader modes", "")
                    return
                }
                currentReaderMode = readerMode as String?
                when (readerMode) {
                    NORMAL_READER_MODE -> {
                        startReading(true)
                    }
                    DISPATCH_READER_MODE -> startReadingWithForegroundDispatch()
                    else -> {
                        result.error("NFCUnknownReaderMode", "unknown reader mode: $readerMode", "")
                        return
                    }
                }
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        tagChannel.setStreamHandler(null)
    }

    override fun onNewIntent(intent: Intent): Boolean {
        val action = intent.action
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)!!
            lastTag = tag
            handleNDEFTagFromIntent(tag)
            return true
        }
        return false;
    }

    private fun getNDEFTagID(ndef: Ndef): String? {
        val idByteArray: ByteArray = ndef.tag.id
        // Fancy string formatting snippet is from
        // https://gist.github.com/luixal/5768921#gistcomment-1788815
        return java.lang.String.format("%0" + idByteArray.size * 2 + "X", BigInteger(1, idByteArray))
    }

    private fun handleNDEFTagFromIntent(tag: Tag) {
        val ndef: Ndef = Ndef.get(tag)
        val formatable: NdefFormatable = NdefFormatable.get(tag)
        val result: Map<*, *> = if (ndef != null) {
            val message: NdefMessage = ndef.cachedNdefMessage
            try {
                ndef.close()
            } catch (e: IOException) {
                Log.e(LOG_TAG, "close NDEF tag error: " + e.message)
            }
            formatNDEFMessageToResult(ndef, message)
        } else if (formatable != null) {
            formatEmptyWritableNDEFMessage()
        } else {
            return
        }
        eventSuccess(result)
    }


    private fun formatEmptyWritableNDEFMessage(): MutableMap<String, Any?> {
        val result: MutableMap<String, Any?> = HashMap()
        result["id"] = ""
        result["message_type"] = "ndef"
        result["type"] = ""
        result["writable"] = true
        val records: MutableList<Map<String, String>> = ArrayList()
        val emptyRecord: MutableMap<String, String> = HashMap()
        emptyRecord["tnf"] = "empty"
        emptyRecord["id"] = ""
        emptyRecord["type"] = ""
        emptyRecord["payload"] = ""
        emptyRecord["data"] = ""
        emptyRecord["languageCode"] = ""
        records.add(emptyRecord)
        result["records"] = records
        return result
    }

    private fun formatEmptyNDEFMessage(ndef: Ndef): Map<String, Any?>? {
        val result = formatEmptyWritableNDEFMessage()
        result["id"] = getNDEFTagID(ndef)
        result["writable"] = ndef.isWritable
        return result
    }

    private fun formatNDEFMessageToResult(ndef: Ndef, message: NdefMessage): Map<*, *> {
        val result: MutableMap<String, Any?> = HashMap()
        val records: MutableList<Map<String, Any>> = ArrayList()
        for (record in message.records) {
            val recordMap: MutableMap<String, Any> = HashMap()
            val recordPayload = record.payload
            var charset: Charset = StandardCharsets.UTF_8
            val tnf = record.tnf
            val type = record.type
            if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
                charset = if ((recordPayload[0] + 1) and 128 == 0) StandardCharsets.UTF_8 else StandardCharsets.UTF_16
            }
            recordMap["rawPayload"] = recordPayload

            // If the record's tnf is well known and the RTD is set to URI,
            // the URL prefix should be added to the payload
            if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_URI)) {
                recordMap["data"] = String(recordPayload, 1, recordPayload.size - 1, charset)
                var url = ""
                var prefixByte = recordPayload[0] + 1;

                when (prefixByte) {
                    0x01 + 1 -> url = "http://www."
                    0x02 + 1 -> url = "https://www."
                    0x03 + 1 -> url = "http://"
                    0x04 + 1 -> url = "https://"
                    0x05 + 1 -> url = "tel:"
                    0x06 + 1 -> url = "mailto:"
                    0x07 + 1 -> url = "ftp://anonymous:anonymous@"
                    0x08 + 1 -> url = "ftp://ftp."
                    0x09 + 1 -> url = "ftps://"
                    0x0A + 1 -> url = "sftp://"
                    0x0B + 1 -> url = "smb://"
                    0x0C + 1 -> url = "nfs://"
                    0x0D + 1 -> url = "ftp://"
                    0x0E + 1 -> url = "dav://"
                    0x0F + 1 -> url = "news:"
                    0x10 + 1 -> url = "telnet://"
                    0x11 + 1 -> url = "imap:"
                    0x12 + 1 -> url = "rtsp://"
                    0x13 + 1 -> url = "urn:"
                    0x14 + 1 -> url = "pop:"
                    0x15 + 1 -> url = "sip:"
                    0x16 + 1 -> url = "sips"
                    0x17 + 1 -> url = "tftp:"
                    0x18 + 1 -> url = "btspp://"
                    0x19 + 1 -> url = "btl2cap://"
                    0x1A + 1 -> url = "btgoep://"
                    0x1B + 1 -> url = "btgoep://"
                    0x1C + 1 -> url = "irdaobex://"
                    0x1D + 1 -> url = "file://"
                    0x1E + 1 -> url = "urn:epc:id:"
                    0x1F + 1 -> url = "urn:epc:tag:"
                    0x20 + 1 -> url = "urn:epc:pat:"
                    0x21 + 1 -> url = "urn:epc:raw:"
                    0x22 + 1 -> url = "urn:epc:"
                    0x23 + 1 -> url = "urn:nfc:"
                    else -> url = ""
                }
                recordMap["payload"] = url + String(recordPayload, 1, recordPayload.size - 1, charset)
            } else if (tnf == NdefRecord.TNF_WELL_KNOWN && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
                val languageCodeLength: Int = (recordPayload[0] and 0x3f) + 1
                recordMap["payload"] = String(recordPayload, 1, recordPayload.size - 1, charset)
                recordMap["languageCode"] = String(recordPayload, 1, languageCodeLength - 1, charset)
                recordMap["data"] = String(recordPayload, languageCodeLength, recordPayload.size - languageCodeLength, charset)
            } else {
                recordMap["payload"] = String(recordPayload, charset)
                recordMap["data"] = String(recordPayload, charset)
            }
            recordMap["id"] = String(record.id, StandardCharsets.UTF_8)
            recordMap["type"] = String(record.type, StandardCharsets.UTF_8)
            var tnfValue: String = when (tnf) {
                NdefRecord.TNF_EMPTY -> "empty"
                NdefRecord.TNF_WELL_KNOWN -> "well_known"
                NdefRecord.TNF_MIME_MEDIA -> "mime_media"
                NdefRecord.TNF_ABSOLUTE_URI -> "absolute_uri"
                NdefRecord.TNF_EXTERNAL_TYPE -> "external_type"
                NdefRecord.TNF_UNCHANGED -> "unchanged"
                else -> "unknown"
            }
            recordMap["tnf"] = tnfValue
            records.add(recordMap)
        }
        result["id"] = getNDEFTagID(ndef)
        result["message_type"] = "ndef"
        result["type"] = ndef.type
        result["records"] = records
        result["writable"] = ndef.isWritable
        return result
    }

    @Throws(IllegalArgumentException::class)
    private fun formatMapToNDEFMessage(map: Map<*, *>): NdefMessage? {
        val mapRecordsObj = map["records"]
        requireNotNull(mapRecordsObj) { "missing records" }
        require(mapRecordsObj is List<*>) { "map key 'records' is not a list" }
        val mapRecords = mapRecordsObj
        val amountOfRecords = mapRecords.size
        val records: Array<NdefRecord?> = arrayOfNulls<NdefRecord>(amountOfRecords)
        for (i in 0 until amountOfRecords) {
            val mapRecordObj = mapRecords[i]!!
            require(mapRecordObj is Map<*, *>) { "record is not a map" }
            val mapRecord = mapRecordObj
            var id = mapRecord["id"] as String?
            if (id == null) {
                id = ""
            }
            var type = mapRecord["type"] as String?
            if (type == null) {
                type = ""
            }
            var languageCode = mapRecord["languageCode"] as String?
            if (languageCode == null) {
                languageCode = Locale.getDefault().language
            }
            var payload = mapRecord["payload"] as String?
            if (payload == null) {
                payload = ""
            }
            val tnf = mapRecord["tnf"] as String?
                    ?: throw IllegalArgumentException("record tnf is null")
            var idBytes: ByteArray? = id.toByteArray()
            var typeBytes: ByteArray? = type.toByteArray()
            val languageCodeBytes: ByteArray = languageCode!!.toByteArray(StandardCharsets.US_ASCII)
            var payloadBytes: ByteArray? = payload.toByteArray()
            var tnfValue: Short
            when (tnf) {
                "empty" -> {
                    // Empty records are not allowed to have a ID, type or payload.
                    tnfValue = NdefRecord.TNF_EMPTY
                    idBytes = null
                    typeBytes = null
                    payloadBytes = null
                }
                "well_known" -> {
                    tnfValue = NdefRecord.TNF_WELL_KNOWN
                    if (Arrays.equals(typeBytes, NdefRecord.RTD_TEXT)) {
                        // The following code basically constructs a text record like NdefRecord.createTextRecord() does,
                        // however NdefRecord.createTextRecord() is only available in SDK 21+ while nfc_in_flutter
                        // goes down to SDK 19.
                        val buffer: ByteBuffer = ByteBuffer.allocate(1 + languageCodeBytes.size + payloadBytes!!.size)
                        val status = (languageCodeBytes.size and 0xFF).toByte()
                        buffer.put(status)
                        buffer.put(languageCodeBytes)
                        buffer.put(payloadBytes)
                        payloadBytes = buffer.array()
                    } else if (Arrays.equals(typeBytes, NdefRecord.RTD_URI)) {
                        // Instead of manually constructing a URI payload with the correct prefix and
                        // everything, create a record using NdefRecord.createUri and copy it's payload.
                        val uriRecord: NdefRecord = NdefRecord.createUri(payload)
                        payloadBytes = uriRecord.getPayload()
                    }
                }
                "mime_media" -> tnfValue = NdefRecord.TNF_MIME_MEDIA
                "absolute_uri" -> tnfValue = NdefRecord.TNF_ABSOLUTE_URI
                "external_type" -> tnfValue = NdefRecord.TNF_EXTERNAL_TYPE
                "unchanged" -> throw IllegalArgumentException("records are not allowed to have their TNF set to UNCHANGED")
                else -> {
                    tnfValue = NdefRecord.TNF_UNKNOWN
                    typeBytes = null
                }
            }
            records[i] = NdefRecord(tnfValue, typeBytes, idBytes, payloadBytes)
        }
        return NdefMessage(records)
    }


    override fun onTagDiscovered(tag: Tag?) {
        lastTag = tag
        val ndef = Ndef.get(tag)
        val formatable = NdefFormatable.get(tag)
        if (ndef != null) {
            var closed = false
            try {
                ndef.connect()
                val message = ndef.ndefMessage
                if (message == null) {
                    eventSuccess(formatEmptyNDEFMessage(ndef)!!)
                    return
                }
                try {
                    ndef.close()
                    closed = true
                } catch (e: IOException) {
                    Log.e(LOG_TAG, "close NDEF tag error: " + e.message)
                }
                eventSuccess(formatNDEFMessageToResult(ndef, message))
            } catch (e: IOException) {
                val details: MutableMap<String, Any> = HashMap()
                details["fatal"] = true
                eventError("IOError", e.message!!, details)
            } catch (e: FormatException) {
                eventError("NDEFBadFormatError", e.message.toString(), null)
            } finally {
                // Close if the tag connection if it isn't already
                if (!closed) {
                    try {
                        ndef.close()
                    } catch (e: IOException) {
                        Log.e(LOG_TAG, "close NDEF tag error: " + e.message)
                    }
                }
            }
        } else if (formatable != null) {
            eventSuccess(formatEmptyWritableNDEFMessage())
        }
    }

    override fun onListen(arguments: Any?, eventss: EventChannel.EventSink?) {
        events = eventss;
    }

    override fun onCancel(arguments: Any?) {
        if (adapter != null) {
            when (currentReaderMode) {
                NORMAL_READER_MODE -> adapter!!.disableReaderMode(activity)
                DISPATCH_READER_MODE -> adapter!!.disableForegroundDispatch(activity)
                else -> Log.e(LOG_TAG, "unknown reader mode: $currentReaderMode")
            }
        }
        events = null
    }

    private fun nfcIsEnabled(): Boolean? {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        return adapter.isEnabled
    }


    private fun startReading(noSounds: Boolean) {
        adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) return
        val bundle = Bundle()
        var flags = DEFAULT_READER_FLAGS
        if (noSounds) {
            flags = flags or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        }
        adapter!!.enableReaderMode(activity, this, flags, bundle)
    }

    private fun startReadingWithForegroundDispatch() {
        adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) return
        val intent = Intent(activity!!.applicationContext, activity!!.javaClass)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent: PendingIntent = PendingIntent.getActivity(activity!!.applicationContext, 0, intent, 0)
        val techList = arrayOf<Array<String>>()
        adapter!!.enableForegroundDispatch(activity, pendingIntent, null, techList)
    }


    private fun eventSuccess(result: Any) {
        val mainThread = Handler(activity!!.mainLooper)
        val runnable = Runnable { events?.success(result) }
        mainThread.post(runnable)
    }

    private fun eventError(code: String, message: String, details: Any?) {
        val mainThread = Handler(activity!!.mainLooper)
        val runnable = Runnable { events?.error(code, message, details) }
        mainThread.post(runnable)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        TODO("Not yet implemented")
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        TODO("Not yet implemented")
    }
}
