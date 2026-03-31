package com.lizongying.mytv0.models

import android.util.Log
import android.util.Xml
import com.lizongying.mytv0.Utils.getDateTimestamp
import com.lizongying.mytv0.data.EPG
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.PushbackInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream


/**
 * EPG XML Parser
 * - Supports gzip compressed streams (.xml.gz), e.g. http://e.erw.cc/all.xml.gz
 * - Preserves 7-day EPG history
 * - Uses display-name as channel key
 * 
 * Fix: Use PushbackInputStream to safely handle non-gzip input.
 * Original bug: GZIPInputStream(inputStream) consumes bytes on failure,
 *               corrupting the stream for XML parsing.
 */
class EPGXmlParser {

    private val ns: String? = null
    private val epg = mutableMapOf<String, MutableList<EPG>>()
    private val channelNames = mutableMapOf<String, String>()  // id -> display-name
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
    private val now = getDateTimestamp()
    private val sevenDaysAgo = now - 604800  // 7 days in seconds (same as v1.4.0.0)

    private fun formatFTime(s: String): Int {
        return dateFormat.parse(s)?.time?.div(1000)?.toInt() ?: 0
    }

    /**
     * Detects gzip format and returns appropriate decompressing stream.
     * Fix: Use PushbackInputStream to safely unread bytes after checking magic.
     */
    private fun decompressIfGzip(inputStream: InputStream): InputStream {
        // Read first 2 bytes to check for gzip magic (0x1f 0x8b)
        val buffer = ByteArray(2)
        val bytesRead = inputStream.read(buffer)
        
        if (bytesRead < 2) {
            // Not enough bytes, return original stream
            return inputStream
        }
        
        // Check gzip magic bytes: 0x1f 0x8b (RFC 1952)
        val isGzip = (buffer[0].toInt() and 0xff) == 0x1f && (buffer[1].toInt() and 0xff) == 0x8b
        
        if (isGzip) {
            // Push bytes back and wrap with PushbackInputStream for GZIPInputStream
            val pushbackStream = PushbackInputStream(inputStream, 2)
            pushbackStream.unread(buffer)
            try {
                val gzipStream = GZIPInputStream(pushbackStream)
                Log.i(TAG, "EPG: gzip detected, decompressing")
                return gzipStream
            } catch (e: Exception) {
                Log.w(TAG, "EPG: gzip decode failed: ${e.message}")
                // Return pushback stream so caller can try reading as plain XML
                return pushbackStream
            }
        } else {
            // Not gzip - push bytes back so caller reads from byte 0
            val pushbackStream = PushbackInputStream(inputStream, 2)
            pushbackStream.unread(buffer)
            Log.i(TAG, "EPG: not gzip, using plain XML stream")
            return pushbackStream
        }
    }

    fun parse(inputStream: InputStream): Map<String, List<EPG>> {
        // Handle gzip detection (e.g. .xml.gz EPG from http://e.erw.cc/all.xml.gz)
        val decompressedStream = decompressIfGzip(inputStream)

        decompressedStream.use { input ->
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            parser.nextTag()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) {
                    parser.next()
                    continue
                }

                if (parser.name == CHANNEL_TAG) {
                    // <channel id="80"><display-name>xxx</display-name></channel>
                    val channelId = parser.getAttributeValue(ns, ID_ATTRIBUTE)
                    parser.nextTag()
                    if (parser.name == DISPLAY_NAME_TAG) {
                        val displayName = parser.nextText()
                        channelNames[channelId] = displayName
                        epg[displayName] = mutableListOf()
                    }
                } else if (parser.name == PROGRAMME_TAG) {
                    // <programme channel="80" start="..." stop="...">
                    val channelId = parser.getAttributeValue(ns, CHANNEL_ATTRIBUTE)
                    val start = parser.getAttributeValue(ns, START_ATTRIBUTE)
                    val stop = parser.getAttributeValue(ns, STOP_ATTRIBUTE)
                    parser.nextTag()
                    val title = parser.nextText()

                    // Preserve programs from the last 7 days
                    val stopTime = formatFTime(stop)
                    if (stopTime > sevenDaysAgo) {
                        val channelName = channelNames[channelId] ?: channelId
                        epg.getOrPut(channelName) { mutableListOf() }
                            .add(EPG(title, formatFTime(start), stopTime))
                    }
                }
                parser.next()
            }
        }

        Log.i(TAG, "EPG loaded: ${epg.size} channels, 7-day history")
        return epg.toSortedMap { a, b -> b.compareTo(a) }
    }

    companion object {
        private const val TAG = "EPGXmlParser"
        private const val CHANNEL_TAG = "channel"
        private const val PROGRAMME_TAG = "programme"
        private const val ID_ATTRIBUTE = "id"
        private const val CHANNEL_ATTRIBUTE = "channel"
        private const val START_ATTRIBUTE = "start"
        private const val STOP_ATTRIBUTE = "stop"
        private const val DISPLAY_NAME_TAG = "display-name"
    }
}
