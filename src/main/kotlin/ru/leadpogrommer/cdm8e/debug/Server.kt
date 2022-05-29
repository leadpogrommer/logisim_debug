package ru.leadpogrommer.cdm8e.debug

import kotlinx.serialization.decodeFromString
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.lang.Exception
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.SwingUtilities
import kotlinx.serialization.*
import kotlinx.serialization.json.*

class Server(val showMessage: (String)->Unit): WebSocketServer(InetSocketAddress(1337)) {
    var currentConnection: WebSocket? = null
    val receiveQueue = ArrayBlockingQueue<CdmRequest>(33)
    init{
        println("Server starting")
    }


    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        if(currentConnection == null){
            currentConnection = conn
            return
        }
        conn.send("Already Connected!")
        conn.close()

    }

    fun sendToClient(message: String){
        currentConnection?.send(message)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        if(conn == currentConnection){
            currentConnection = null
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if(currentConnection != conn)return
        receiveQueue.add(Json.decodeFromString(message))
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        System.err.println("[WS] Error: ${ex?.message}")
        showMessage("[WS] Error: ${ex?.message}")
    }

    override fun onStart() {
        println("Server started")
        showMessage("Debug server started")
        val tickControlThread = TickControlThread(receiveQueue, ::sendToClient)
        tickControlThread.isDaemon = true
        tickControlThread.start()
    }
}

class TickControlThread(val receiveQueue: BlockingQueue<CdmRequest>, val sendMessage: (msg: String)->Unit): Thread(){
    var breakpoints: List<Int> = emptyList()
    var lineLocations: List<Int> = emptyList()
    var running = false

    override fun run() {
        while (true){
            handleMessage()
        }
    }
    private fun handleMessage(block: Boolean = true){
        if(!block && receiveQueue.isEmpty()){
            return
        }
        val msg = receiveQueue.take()
        val action = msg.action
        when(action){
            "breakpoints" -> breakpoints = msg.data!!
            "line_locations" -> lineLocations = msg.data!!
            "pause" -> running = false
            "continue" -> runSimulation(breakOnLine = false)
            "step" -> runSimulation(breakOnLine = true)
            "path" -> {
                DebugTool.INSTANCE.loadAndRestart(msg.path!!)
                sendMessage(Json.encodeToString(CdmStateResponse(CdmState(Array(256){0}.toList(), CdmRegisters(0, 0, 0, 0, 0, 0, 0)), "state")))
            }
        }
    }

    private fun runSimulation(breakOnLine: Boolean){
        if(running)return
        var stopReason = "pause"
        running = true

        val state = DebugTool.INSTANCE.tickUntil {
            // it executes in different thread, but only while our thread is blocked
            handleMessage(false)
            !running || breakpoints.contains(it.registers.pc) || (breakOnLine && lineLocations.contains(it.registers.pc))
        }

        running = false
        sendMessage(Json.encodeToString(mapOf("action" to "stop")))
        // because kotlin serialization is bullshit
        sendMessage(Json.encodeToString(CdmStateResponse(state, "state")))
    }
}

@Serializable
data class CdmStateResponse(val data: CdmState, val action: String){

}

@kotlinx.serialization.Serializable
data class CdmRequest(val action: String, val data: List<Int>? = null, val path: String? =null)