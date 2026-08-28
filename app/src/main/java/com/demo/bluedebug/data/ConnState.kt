package com.demo.bluedebug.data

class ConnState{

    companion object{
        val IDLE = 0
        val CONNECTING = 1
        val CONNECTED = 2
        val DISCONNECTED = 3
    }

    private var curState = 0;

    fun getCurState(): Int = curState
     fun updateState(state: Int): Unit {
         curState = state
     }
}