package com.demo.bluedebug.data

enum class MsgLevel{  LOG,TOAST,BOTH }
data class UiMessage(val text: String,val level: MsgLevel)