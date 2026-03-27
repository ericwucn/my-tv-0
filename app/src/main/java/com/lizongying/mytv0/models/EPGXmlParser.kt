package com.lizongying.mytv0.models

import android.util.Log
import android.util.Xml
import com.lizongying.mytv0.Utils.getDateTimestamp
import com.lizongying.mytv0.data.EPG
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale


/**
 * EPG XML ???
 *
 * ????:
 * - ?? XMLTV (xmltv.dtd) ??? EPG ??
 * - ?? 7 ? EPG(????????)
 * - ???????? (category tag)
 */
class EPGXmlParser {

    private val ns: String? = null
    private val epg = mutableMapOf<String, MutableList<EPG>>()
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
    // ===== ??:7?EPG?? =====
    // ????:?????????????????
    private val keepCurrentEpisode = true
    // 7????(7 * 24 * 60 * 60 = 604800?)
    private val epgDays = 7
    private val epgExpiredThreshold = getDateTimestamp() - (epgDays * 24 * 60 * 60)
    // ?????
    private val now = getDateTimestamp()

    private fun formatFTime(s: String): Int {
        return dateFormat.parse(s)?.time?.div(1000)?.toInt() ?: 0
    }

    fun parse(inputStream: InputStream): Map<String, List<EPG>> {
        inputStream.use { input ->
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
                    val startTime = formatFTime(start)
                    val stopTime = formatFTime(stop)

                    parser.nextTag()
                    var title = ""
                    var category = ""

                    // ?????
                    while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == PROGRAMME_TAG)) {
                        if (parser.eventType == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "title" -> {
                                    parser.nextTag()
                                    title = parser.nextText()
                                }
                                "category" -> {
                                    parser.nextTag()
                                    category = parser.nextText()
                                }
                            }
                        }
                        parser.next()
                    }

                    // ===== 7?EPG???? =====
                    // ??1: ?????? > 7??(??7????)
                    // ??2: ?????? < 7??(???????)
                    // ??3(??): ???????????(????)
                    val isWithin7Days = stopTime > epgExpiredThreshold
                    val isNotTooFar = startTime < (now + epgDays * 24 * 60 * 60)
                    val isCurrentProgram = keepCurrentEpisode && startTime <= now && stopTime >= now

                    if (isWithin7Days && (isNotTooFar || isCurrentProgram)) {
                        epg[channel]?.add(EPG(title, startTime, stopTime, category))
                    }
                }

                parser.next()
            }
        }

        Log.i(TAG, "EPG????: ${epg.size} ???, 7?EPG??")
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
