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
 * - ?? gzip ???? (.xml.gz) ????,?? http://e.erw.cc/all.xml.gz ? EPG ?
 * - ?? 7 ? EPG ??(???,???? 7 ???)
 * - ?? display-name ???? key,?????????
 */
class EPGXmlParser {

    private val ns: String? = null
    private val epg = mutableMapOf<String, MutableList<EPG>>()
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
    private val now = getDateTimestamp()
    // 7 ?????,??????
    private val sevenDaysAgo = now - 7 * 24 * 60 * 60

    private fun formatFTime(s: String): Int {
        return dateFormat.parse(s)?.time?.div(1000)?.toInt() ?: 0
    }

    /**
     * ??????? gzip ?????
     * ?? .xml.gz ?? EPG ?(? http://e.erw.cc/all.xml.gz)
     */
    private fun decompressIfGzip(inputStream: InputStream): InputStream {
        return try {
            val gzipStream = GZIPInputStream(inputStream)
            Log.i(TAG, "EPG: gzip ??,????")
            gzipStream
        } catch (e: Exception) {
            Log.i(TAG, "EPG: ?? XML ??")
            inputStream
        }
    }

    fun parse(inputStream: InputStream): Map<String, List<EPG>> {
        // ???? gzip(?? .xml.gz EPG ?)
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
                    // ?? 7 ????(???)
                    if (stopTime > sevenDaysAgo) {
                        epg[channel]?.add(EPG(title, formatFTime(start), stopTime))
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
        private const val START_ATTRIBUTE = "start"
        private const val STOP_ATTRIBUTE = "stop"
    }
}