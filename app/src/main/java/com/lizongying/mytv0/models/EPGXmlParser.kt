package com.lizongying.mytv0.models

import android.util.Xml
import com.lizongying.mytv0.Utils.getDateTimestamp
import com.lizongying.mytv0.data.EPG
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale


class EPGXmlParser {

    private val ns: String? = null
    private val epg = mutableMapOf<String, MutableList<EPG>>()
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
    private val now = getDateTimestamp()
    // 7-day replay/catchup support: calculate timestamp for 7 days ago
    private val sevenDaysAgo = now - (7 * 24 * 3600)

    private fun formatFTime(s: String): Int {
        return dateFormat.parse(s)?.time?.div(1000)?.toInt() ?: 0
    }

    fun parse(inputStream: InputStream): Map<String, List<EPG>> {
        inputStream.use { input ->
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            parser.nextTag()
            var currentChannel = ""
            
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) {
                    parser.next()
                    continue
                }
                
                if (parser.name == CHANNEL_TAG) {
                    // Parse channel: <channel id="80"><display-name>东方卫视</display-name></channel>
                    // Use the 'id' attribute as the key (matches M3U tvg-id)
                    currentChannel = parser.getAttributeValue(ns, ID_ATTRIBUTE)
                    epg[currentChannel] = mutableListOf()
                    // Skip display-name
                    parser.nextTag()
                    parser.nextText()
                } else if (parser.name == PROGRAMME_TAG) {
                    // Parse programme: <programme channel="80" start="..." stop="...">
                    val channel = parser.getAttributeValue(ns, CHANNEL_ATTRIBUTE)
                    val start = parser.getAttributeValue(ns, START_ATTRIBUTE)
                    val stop = parser.getAttributeValue(ns, STOP_ATTRIBUTE)
                    parser.nextTag()
                    val title = parser.nextText()
                    
                    // 7-day replay: show programmes that ended within the last 7 days
                    val stopTime = formatFTime(stop)
                    if (stopTime > sevenDaysAgo) {
                        epg[channel]?.add(EPG(title, formatFTime(start), stopTime))
                    }
                }
                parser.next()
            }
        }

        return epg.toSortedMap { a, b -> b.compareTo(a) }
    }

    companion object {
        private const val CHANNEL_TAG = "channel"
        private const val PROGRAMME_TAG = "programme"
        private const val ID_ATTRIBUTE = "id"
        private const val CHANNEL_ATTRIBUTE = "channel"
        private const val START_ATTRIBUTE = "start"
        private const val STOP_ATTRIBUTE = "stop"
    }
}
