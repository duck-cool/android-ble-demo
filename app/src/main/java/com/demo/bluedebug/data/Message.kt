package com.demo.bluedebug.data

enum class MsgLevel{  LOG,TOAST,BOTH }
enum class LogLevel{ INFO,WARN,ERROR }
data class UiMessage(val text: String,val level: MsgLevel,val severity: LogLevel)