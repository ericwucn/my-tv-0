package com.lizongying.mytv0.models

import android.util.Log
import android.util.Xml
import com.lizongying.mytv0.Utils.getDateTimestamp
import com.lizongying.mytv0.data.EPG
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream


/**
 * EPG XML ???
 * - ?? gzip ???? (.xml.gz) ????
 * - ?? 7 ? EPG ??(???)
 * - ?? display-name ????,???????
 */
class EPGXmlParser {

    private val ns: String? = null
    private val epg = mutableMapOf<String, MutableList<EPG>>()
    private val channelNames = mutableMapOf<String, String>()  // id -> display-name
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
    private val now = getDateTimestamp()
    private val sevenDaysAgo = now - 604800  // 7 ??,????

    private fun formatFTime(s: String): Int {
        return dateFormat.parse(s)?.time?.div(1000)?.toInt() ?: 0
    }

    /**
     * ??????? gzip ??(?? .xml.gz EPG ?)
     */
    private fun decompressIfGzip(inputStream: InputStream): InputStream {
        return try {
            val gzipStream = GZIPInputStream(inputStream)
            Log.i(TAG, "gzip ??,????")
            gzipStream
        } catch (e: Exception) {
            Log.i(TAG, "?? XML ??")
            inputStream
        }
    }

    fun parse(inputStream: InputStream): Map<String, List<EPG>> {
        // ???? gzip(?? http://e.erw.cc/all.xml.gz ??? EPG ?)
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
                    // <channel id="80"><display-name>????</display-name></channel>
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

                    // 7 ??????(????)
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

        Log.i(TAG, "EPG ????: ${epg.size} ???,7???")
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