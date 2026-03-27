package com.lizongying.mytv0.data


data class EPG(
    val title: String,
    val beginTime: Int,
    val endTime: Int,
    val category: String = "",  // ??????,??7?EPG
)
