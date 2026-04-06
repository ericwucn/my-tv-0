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
 * EPG XML Parser
 * 
 * 功能：
 * - 解析 XMLTV 格式的 EPG 数据
 * - 支持 gzip 压缩的流 (.xml.gz)
 * - 保留 7 天 EPG 历史（动态计算时间范围）
 * - 使用 display-name 作为 channel key
 * 
 * 支持的 XML 格式：
 * <channel id="80"><display-name>CCTV1</display-name></channel>
 * <programme channel="80" start="20240101120000 +0800" stop="20240101130000 +0800">
 *   <title>新闻联播</title>
 * </programme>
 */
class EPGXmlParser {

    private val ns: String? = null
    private val epg = mutableMapOf<String, MutableList<EPG>>()
    private val channelNames = mutableMapOf<String, String>()  // id -> display-name
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())

    /**
     * 动态获取 7 天前的时间戳
     * 避免硬编码导致长时间运行后时间范围不准确
     */
    private fun getSevenDaysAgo(): Long {
        return getDateTimestamp() - 604800  // 7 天 = 7 * 24 * 60 * 60
    }

    private fun formatFTime(s: String): Int {
        return dateFormat.parse(s)?.time?.div(1000)?.toInt() ?: 0
    }

    /**
     * 自动检测并解压 gzip 流
     * 直接尝试 GZIPInputStream，失败则返回原始流
     */
    private fun decompressIfGzip(inputStream: InputStream): InputStream {
        return try {
            val gzipStream = GZIPInputStream(inputStream)
            Log.i(TAG, "EPG gzip 解码成功")
            gzipStream
        } catch (e: Exception) {
            Log.i(TAG, "EPG 普通 XML 流")
            inputStream
        }
    }

    /**
     * 解析 EPG XML 数据
     * @param inputStream XML 或 gzip 压缩的 XML 流
     * @return 频道名 -> 节目列表 的映射
     */
    fun parse(inputStream: InputStream): Map<String, List<EPG>> {
        // 处理 gzip
        val decompressedStream = decompressIfGzip(inputStream)
        
        // 动态计算时间范围
        val sevenDaysAgo = getSevenDaysAgo()
        val now = getDateTimestamp()
        Log.i(TAG, "EPG 时间范围: ${sevenDaysAgo} - $now (保留 7 天历史)")

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

                    // 保留最近 7 天的节目（动态计算）
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

        Log.i(TAG, "EPG 加载完成: ${epg.size} 个频道，保留 7 天历史")
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