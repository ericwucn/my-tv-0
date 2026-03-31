package com.lizongying.mytv0.models

import android.util.Log
import android.util.Xml
import com.lizongying.mytv0.Utils.getDateTimestamp
import com.lizongying.mytv0.data.EPG
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
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
 */
class EPGXmlParser {

    private val ns: String? = null
    private val epg = mutableMapOf<String, MutableList<EPG>>()
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
    private val now = getDateTimestamp()
    // 7 days ago, for preserving historical EPG
    private val sevenDaysAgo = now - 7 * 24 * 60 * 60

    private fun formatFTime(s: String): Int {
        return dateFormat.parse(s)?.time?.div(1000)?.toInt() ?: 0
    }

    /**
     * Detects gzip format and returns appropriate decompressing stream.
     * Fix: check magic bytes (0x1f 0x8b) BEFORE deciding.
     * OLD BUG: GZIPInputStream(inputStream) consumes bytes on failure, corrupting
     *          the stream when input is NOT gzip.
     */
    private fun decompressIfGzip(inputStream: InputStream): InputStream {
        // Buffer to get mark/reset support
        val buffered = BufferedInputStream(inputStream)
        buffered.mark(2)
        val magic = ByteArray(2)
        val bytesRead = buffered.read(magic)

        if (bytesRead < 2) {
            // Not enough bytes, return as-is
            buffered.reset()
            return buffered
        }

        val isGzip = (magic[0].toInt() and 0xff) == 0x1f && (magic[1].toInt() and 0xff) == 0x8b

        if (isGzip) {
            // Reset and let GZIPInputStream read from beginning
            buffered.reset()
            try {
                val gzipStream = GZIPInputStream(buffered)
                Log.i(TAG, "EPG: gzip detected, decompressing")
                return gzipStream
            } catch (e: Exception) {
                Log.w(TAG, "EPG: gzip decode failed: ${e.message}")
                buffered.reset()
                return buffered
            }
        } else {
            // Not gzip - reset so caller reads from byte 0
            buffered.reset()
            Log.i(TAG, "EPG: not gzip, using plain XML stream")
            return buffered
        }
    }

    fun parse(inputStream: InputStream): Map<String, List<EPG>> {
        val decompressedStream = decompressIfGzip(inputStream)

        decompressedStream.use { input ->
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            parser.nextTag()
            var channel = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) {
                    parser.next()
                    continue
                }
                if (parser.name == CHANNEL_TAG) {
                    parser.nextTag()
                    channel = parser.nextText()
                    epg[channel] = mutableListOf()
                } else if (parser.name == PROGRAMME_TAG) {
                    val start = parser.getAttributeValue(ns, START_ATTRIBUTE)
                    val stop = parser.getAttributeValue(ns, STOP_ATTRIBUTE)
                    parser.nextTag()
                    val title = parser.nextText()
                    val stopTime = formatFTime(stop)
                    // Preserve programs from the last 7 days
                    if (stopTime > sevenDaysAgo) {
                        epg[channel]?.add(EPG(title, formatFTime(start), stopTime))
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
        private const val START_ATTRIBUTE = "start"
        private const val STOP_ATTRIBUTE = "stop"
    }
}
