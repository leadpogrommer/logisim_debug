package ru.leadpogrommer.cdm8e.debug

import com.cburch.hex.HexModel
import com.cburch.logisim.circuit.CircuitState
import com.cburch.logisim.circuit.SimulatorEvent
import com.cburch.logisim.circuit.SimulatorListener
import com.cburch.logisim.circuit.SubcircuitFactory
import com.cburch.logisim.comp.Component
import com.cburch.logisim.data.Attribute
import com.cburch.logisim.gui.main.Canvas
import com.cburch.logisim.instance.InstancePainter
import com.cburch.logisim.std.memory.Ram
import com.cburch.logisim.std.memory.Rom
import com.cburch.logisim.tools.AddTool
import com.cburch.logisim.tools.Library
import com.cburch.logisim.tools.Tool
import com.cburch.logisim.util.GraphicsUtil
import java.awt.Graphics
import java.awt.event.MouseEvent
import com.cburch.logisim.data.Location
import com.cburch.logisim.instance.Port
import com.cburch.logisim.proj.Project
import com.cburch.logisim.std.wiring.Tunnel
import java.io.File
import java.io.IOError
import java.io.IOException
import java.util.StringJoiner
import javax.swing.SwingUtilities
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class Components : Library() {
    private val tools = mutableListOf(AddTool(DebugRam()), AddTool(DebugRom()), DebugTool())

    override fun getTools() = tools
    override fun getDisplayName() = "Cdm8e debug"
}

const val CDM_NAME = "CdM_8_mark5"



// TODO: does not support multiple projects
// Cdm8, ram and rom must be at top level circuit
class DebugTool: Tool(){
    var initialized = false
    init {
        if(wasInstantiated)throw java.lang.IllegalStateException("${this::class.simpleName} instantiated twice")
        wasInstantiated = true
        INSTANCE = this
    }
    lateinit var project: Project
    lateinit var server: Server
    lateinit var serverThread: Thread

    override fun select(canvas: Canvas?) {
        super.select(canvas)
        canvas ?: return
        if(!initialized){
            initialize(canvas)
            initialized = true
        }
    }

    private fun initialize(canvas: Canvas){
        val simulator = canvas.project.simulator
        simulator.addSimulatorListener(object :SimulatorListener{
            override fun propagationCompleted(p0: SimulatorEvent?) {}
            override fun simulatorStateChanged(p0: SimulatorEvent?) {}

            override fun tickCompleted(p0: SimulatorEvent?) {
                p0 ?: return
                onTickComplete(p0)
            }
        })
        project = canvas.project
        server = Server()
        serverThread = Thread(server)
        serverThread.isDaemon = true
        serverThread.start()
    }

    private var tickPredicate: (state: CdmState) -> Boolean = {true}
    @Volatile
    private var tickCompleted = true
    private lateinit var tickResult: CdmState
    private val tickLock = Object()
    private var bootstrapTicks = 0

    fun loadAndRestart(path: String){
        SwingUtilities.invokeAndWait {
            var topState = project.circuitState
            while (topState.parentState != null) topState = topState.parentState
            val components = topState.circuit.getAllWithin(topState.circuit.bounds)
            val romComponent = components.find { it.factory is DebugRom } ?: return@invokeAndWait
            val romState = topState.getInstanceState(romComponent)
            try {
                (romState.factory as DebugRom).loadImage(romState, File(path))
            }catch (e: IOException){
                println("load failed")
            }catch (e: IOError){
                println("load failed")
            }
            project.simulator.requestReset()
        }
    }

    // should be called from separate thread
    // but only once at time
    // predicate is called in propagator thread
    fun tickUntil(predicate: ((state: CdmState) -> Boolean)): CdmState{
        tickCompleted = false
        bootstrapTicks = 2
        tickPredicate = predicate
        SwingUtilities.invokeLater {
            // will be called in UI thread
            project.simulator.tick()
        }
        synchronized(tickLock){
            while (!tickCompleted){
                tickLock.wait()
            }
        }
        return tickResult
    }

    fun onTickComplete(event: SimulatorEvent){
        bootstrapTicks--
        val state = getCdmState(event.source.circuitState)
        if(bootstrapTicks < 0 && state != null && tickPredicate(state)) {
            tickCompleted = true
            tickResult = state
            synchronized(tickLock){
                tickLock.notifyAll()
            }
        }else{
            event.source.tick()
        }
    }

    fun getCdmState(state: CircuitState): CdmState?{
        var topState = state
        while (topState.parentState != null)topState = topState.parentState
        val components = topState.circuit.getAllWithin(topState.circuit.bounds)

        val ramComponent = components.find { it.factory is DebugRam } ?: return null
        val cdmComponent = components.find { it.factory is SubcircuitFactory && it.factory.name == "CdM_8_mark5" } ?: return null

        val cdmSubcircuit = (cdmComponent.factory as SubcircuitFactory).subcircuit

        val cdmTunnels = cdmSubcircuit.nonWires.filter { it.factory is Tunnel }



        // TODO: cache components
        val cdmState = cdmSubcircuit.subcircuitFactory.getSubstate(topState, cdmComponent)

        val isFetching = getTunnelValue(cdmTunnels, cdmState, "fetch")
        if(isFetching == 0)return null

        // now we sure it is time

        val registers = CdmRegisters(
            getTunnelValue(cdmTunnels, cdmState, "r0"),
            getTunnelValue(cdmTunnels, cdmState, "r1"),
            getTunnelValue(cdmTunnels, cdmState, "r2"),
            getTunnelValue(cdmTunnels, cdmState, "r3"),
            getTunnelValue(cdmTunnels, cdmState, "PSR"),
            getTunnelValue(cdmTunnels, cdmState, "PC"),
            getTunnelValue(cdmTunnels, cdmState, "SP"),
        )

        val ramState = topState.getData(ramComponent)

        // because MemState is not public
        val memStateClass = Class.forName("com.cburch.logisim.std.memory.MemState").kotlin
        val contentsProperty = memStateClass.memberProperties.find { it.name == "contents" }!!
        contentsProperty.isAccessible = true
        val memHexModel = contentsProperty.getter.call(ramState) as HexModel

        val ramData = (0 .. memHexModel.lastOffset).map { memHexModel[it] }

        return CdmState(ramData, registers)
    }

    fun getTunnelValue(tunnels: List<Component>, state: CircuitState, name: String): Int{
        val tunnel = tunnels.find { tunnel ->
            (tunnel.attributeSet.getValue(tunnel.attributeSet.getAttribute("label")) as String) == name
        }?: return 0
        val value = state.getValue(tunnel.ends[0].location)
        if(!value.isFullyDefined) return 0
        return value.toIntValue()
    }

    override fun mousePressed(canvas: Canvas?, graphics: Graphics?, event: MouseEvent?) {
        super.mousePressed(canvas, graphics, event)
        event ?: return
        canvas ?: return
        graphics ?: return
        val loc = Location.create(event.x, event.y)


        val components = canvas.circuit.getAllContaining(loc, graphics)
        for (component in components){
            println(component.factory::class.simpleName)
            val factory = component.factory
            when (factory){
                is DebugRom -> {
                    println("debugRom selected")
                }
                is DebugRam -> {
                    println("DebugRam selected")
                }
                is SubcircuitFactory -> {
                    println(factory.name)
                }
            }
        }
    }

    override fun getName() = "Debug tool"
    override fun getDisplayName() = "Debug tool"
    override fun getDescription() = "Super cool cdm8e debug tool"
    companion object{
        var wasInstantiated = false
        lateinit var INSTANCE: DebugTool
    }
}

class DebugRam: Ram(){
    override fun paintInstance(painter: InstancePainter?) {
        super.paintInstance(painter)
        painter?:return
        val bounds = painter.bounds
        GraphicsUtil.drawText(painter.graphics, "DBG", bounds.x, bounds.y, GraphicsUtil.H_LEFT, GraphicsUtil.V_TOP)
    }
}

class DebugRom: Rom(){
    override fun paintInstance(painter: InstancePainter?) {
        super.paintInstance(painter)
        painter?:return
        val bounds = painter.bounds
        GraphicsUtil.drawText(painter.graphics, "DBG", bounds.x, bounds.y, GraphicsUtil.H_LEFT, GraphicsUtil.V_TOP)
    }
}