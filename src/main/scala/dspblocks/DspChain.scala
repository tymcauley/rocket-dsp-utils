// See LICENSE for license details

package dspblocks

import cde._
import chisel3._
import craft._
import debuggers._
import _root_.junctions._
import diplomacy._
import dspjunctions._
import sam._
import testchipip._
import uncore.converters._
import uncore.tilelink._
import junctions._
import rocketchip._

object DspChain {
  private[dspblocks] val _addrMaps: scala.collection.mutable.Map[String, AddrMap] =
    scala.collection.mutable.Map()

  def addrMap(id: String): AddrMap = _addrMaps(id)
  def addrMapIds(): Seq[String] = _addrMaps.keys.toSeq
}

case class BlockConnectionParameters (
  connectPG: Boolean = true,
  connectLA: Boolean = true,
  addSAM: Boolean = true
)

object BlockConnectEverything extends BlockConnectionParameters(true, true, true)
object BlockConnectNothing extends BlockConnectionParameters(false, false, false)
object BlockConnectSAMOnly extends BlockConnectionParameters(false, false, true)
object BlockConnectPGLAOnly extends BlockConnectionParameters(true, true, false)

case class DspChainParameters (
  blocks: Seq[(Parameters => DspBlock, String, BlockConnectionParameters, Option[SAMConfig])],
  logicAnalyzerSamples: Int,
  logicAnalyzerUseCombinationalTrigger: Boolean,
  patternGeneratorSamples: Int,
  patternGeneratorUseCombinationalTrigger: Boolean,
  biggestWidth: Int = 128,
  writeHeader: Boolean = false
)

case object DspChainId extends Field[String]
case class DspChainKey(id: String) extends Field[DspChainParameters]

trait HasDspChainParameters extends HasSCRParameters {
  val p: Parameters
  val dspChainExternal                        = p(DspChainKey(p(DspChainId)))
  val id                                      = p(DspChainId)
  val blocks                                  = dspChainExternal.blocks
  val blocksUsePG                             = blocks.map(_._3.connectPG)
  val blocksUseLA                             = blocks.map(_._3.connectLA)
  val blocksUseSAM                            = blocks.map(_._3.addSAM)
  val blocksSAMConfigs                        = blocks.map(_._4)
  val totalPGBlocks                           = blocksUsePG.foldLeft(0) { case (sum, b) => if (b) sum + 1 else sum }
  val totalLABlocks                           = blocksUseLA.foldLeft(0) { case (sum, b) => if (b) sum + 1 else sum }
  val totalSAMBlocks                          = blocksUseSAM.foldLeft(0) { case(sum, b) => if (b) sum + 1 else sum }
  val logicAnalyzerSamples                    = dspChainExternal.logicAnalyzerSamples
  val logicAnalyzerUseCombinationalTrigger    = dspChainExternal.logicAnalyzerUseCombinationalTrigger
  val patternGeneratorSamples                 = dspChainExternal.patternGeneratorSamples
  val patternGeneratorUseCombinationalTrigger = dspChainExternal.patternGeneratorUseCombinationalTrigger

  val biggestWidth                            = dspChainExternal.biggestWidth
  // find biggest IO
  /*val biggestWidth = mod_ios.foldLeft(0){
    case(maximum, io) =>
      math.max(maximum,
        math.max(io.in.bits.getWidth, io.out.bits.getWidth))
  }*/
  val wordWidth = scrDataBits
  val dataWidthWords = (biggestWidth + wordWidth-1) / wordWidth

  def writeHeader = dspChainExternal.writeHeader
}

trait WithChainHeaderWriter { this: DspChainModule =>
  private val badTokens = Seq("-", ":")
  def nameMangle(id: String): String =
    badTokens.foldLeft(id) {case (str, token) => str.replace(token, "_") }
  def getSamOffset(regName: String) =
    flattenedSams.head.addrmap(regName) - flattenedSams.head.baseAddr
  def annotateHeader = {
    object Chain {
      val chain = nameMangle(id)
      val chain_scr = SCRAddressMap(outer.scrName).get.map({case (key, value) =>
        Map(
          "addrname"  -> key,
          "addr"      -> value,
          "blockname" -> nameMangle(outer.scrName)
        )
      })
      val blocks = modules.map(mod => {
        Map(
          "addrs"     ->
            mod.addrmap.map({case (key, value) =>
              Map(
                "addrname"  -> key,
                "addr"      -> value,
                "blockname" -> nameMangle(mod.id)
              )
            })
        )})
      val sam = flattenedSams.map(s => {
        Map(
          "samname"   -> nameMangle(s.id),
          "ctrl_base" -> s.baseAddr,
          "data_base" -> s.dataBaseAddr
          )
      })
      val samWStartAddrOffset   = getSamOffset("samWStartAddr")
      val samWTargetCountOffset = getSamOffset("samWTargetCount")
      val samWTrigOffset        = getSamOffset("samWTrig")
      val samWWaitForSyncOffset = getSamOffset("samWWaitForSync")
      val samWWriteCountOffset  = getSamOffset("samWWriteCount")
      val samWPacketCountOffset = getSamOffset("samWPacketCount")
      val samWSyncAddrOffset    = getSamOffset("samWSyncAddr")
    }
    val header: String = {
      import com.gilt.handlebars.scala.binding.dynamic._
      import com.gilt.handlebars.scala.Handlebars
      import scala.io.Source

      val stream = getClass.getResourceAsStream("/chain_api.h")
      val template = Source.fromInputStream( stream ).getLines.mkString("\n")
      val t= Handlebars(template)
      t(Chain)
    }
    import java.io.{File, PrintWriter}
    val writer = new PrintWriter(new File("chain_api.h"))
    writer.write(header)
    writer.close()
  }
}

trait HasDecoupledSCR {
  /*
   * Helper for creating SCR interface for Decoupled IOs
   * Inputs are strings that look up entries in the scr file
   * Returns a pair of Bools. The first is valid and the second is finished
   */
  def decoupledHelper(valid: Bool, ready: Bool, writeEn: Bool, writeFinishedStatus: UInt) = {
    val writeFinished = Reg(init=false.B)
    val prevWriteEn = Reg(next=writeEn)
    when (!writeEn) {
      writeFinished := false.B
    }
    when (writeEn && !prevWriteEn) {
      writeFinished := false.B
    }
    when (writeEn && ready) {
      writeFinished := true.B
    }
    writeFinishedStatus := writeFinished
    valid := writeEn && !writeFinished
    ()
  }
}

trait HasPatternGenerator extends HasDspChainParameters {
  def scrbuilder: SCRBuilder

  scrbuilder.addControl("patternGeneratorEnable", 0.U)
  scrbuilder.addControl("patternGeneratorSelect", 0.U)
  scrbuilder.addControl("patternGeneratorReadyBypass")
  scrbuilder.addControl("patternGeneratorTriggerMode")
  scrbuilder.addControl("patternGeneratorLastSample")
  scrbuilder.addControl("patternGeneratorContinuous")
  scrbuilder.addControl("patternGeneratorArm")
  scrbuilder.addControl("patternGeneratorAbort")
  scrbuilder.addControl("patternGeneratorControlEnable")
  scrbuilder.addStatus("patternGeneratorControlFinished")
  scrbuilder.addControl("patternGeneratorWriteAddr")

  for (i <- 0 until dataWidthWords) {
    scrbuilder.addControl(s"patternGeneratorWriteData_$i")
  }
  scrbuilder.addControl("patternGeneratorWriteEnable")
  scrbuilder.addStatus("patternGeneratorWriteFinished")
  scrbuilder.addStatus("patternGeneratorState")
  scrbuilder.addStatus("patternGeneratorNumSampled")
  scrbuilder.addStatus("patternGeneratorStarted")
  scrbuilder.addStatus("patternGeneratorOverflow")
}

trait HasLogicAnalyzer extends HasDspChainParameters {
  def scrbuilder: SCRBuilder

  scrbuilder.addControl("logicAnalyzerEnable", 0.U)
  scrbuilder.addControl("logicAnalyzerSelect", 0.U)
  scrbuilder.addControl("logicAnalyzerValidBypass")
  scrbuilder.addControl("logicAnalyzerTriggerMode")
  scrbuilder.addControl("logicAnalyzerNumSamples")
  scrbuilder.addControl("logicAnalyzerArm")
  scrbuilder.addControl("logicAnalyzerAbort")
  scrbuilder.addControl("logicAnalyzerControlEnable")
  scrbuilder.addStatus("logicAnalyzerControlFinished")
  scrbuilder.addControl("logicAnalyzerWriteAddr")

  for (i <- 0 until dataWidthWords) {
    scrbuilder.addStatus(s"logicAnalyzerWriteData_$i")
  }
  scrbuilder.addControl("logicAnalyzerWriteEnable")
  scrbuilder.addStatus("logicAnalyzerWriteFinished")
  scrbuilder.addStatus("logicAnalyzerState")
  scrbuilder.addStatus("logicAnalyzerNumSampled")
  scrbuilder.addStatus("logicAnalyzerOverflow")
}

trait HasPatternGeneratorModule extends HasDspChainParameters with HasDecoupledSCR {
  def scrfile: SCRFile
  def dataWidthWords: Int
  def wordWidth: Int

  lazy val patternGenerator = {
    val patternGenerator = Module(new PatternGenerator(
      dataWidthWords*wordWidth, 1,
      patternGeneratorSamples,
      patternGeneratorUseCombinationalTrigger)
    )

    scrfile.status("patternGeneratorState")      :=
      patternGenerator.io.status.state
    scrfile.status("patternGeneratorNumSampled") :=
      patternGenerator.io.status.numSampled
    scrfile.status("patternGeneratorStarted")    :=
      patternGenerator.io.status.started
    scrfile.status("patternGeneratorOverflow")   :=
      patternGenerator.io.status.overflow

    patternGenerator.io.memory.bits.writeAddr :=
      scrfile.control("patternGeneratorWriteAddr")

    val patternGeneratorWriteData = (0 until dataWidthWords).map(i =>
        scrfile.control(s"patternGeneratorWriteData_$i")
        )

    patternGenerator.io.memory.bits.writeData(0) :=
      patternGeneratorWriteData.reverse.reduce({(x:UInt, y:UInt) => util.Cat(x,y) })

    decoupledHelper(
      patternGenerator.io.memory.valid,
      patternGenerator.io.memory.ready,
      scrfile.control("patternGeneratorWriteEnable") =/= 0.U,
      scrfile.status("patternGeneratorWriteFinished"))

    patternGenerator.io.control.bits.readyBypass :=
      scrfile.control("patternGeneratorReadyBypass")
    patternGenerator.io.control.bits.triggerMode :=
      scrfile.control("patternGeneratorTriggerMode")
    patternGenerator.io.control.bits.lastSample :=
      scrfile.control("patternGeneratorLastSample")
    patternGenerator.io.control.bits.continuous :=
      scrfile.control("patternGeneratorContinuous")
    patternGenerator.io.control.bits.arm :=
      scrfile.control("patternGeneratorArm")
    patternGenerator.io.control.bits.abort :=
      scrfile.control("patternGeneratorAbort")

    decoupledHelper(
      patternGenerator.io.control.valid,
      patternGenerator.io.control.ready,
      scrfile.control("patternGeneratorControlEnable") =/= 0.U,
      scrfile.status("patternGeneratorControlFinished"))


    patternGenerator.io.signal.ready := scrfile.control("patternGeneratorEnable")

    patternGenerator
  }

  lazy val patternGeneratorSelects = (0 until totalPGBlocks).map(i =>
      scrfile.control("patternGeneratorSelect") === i.U &&
      scrfile.control("patternGeneratorEnable") =/= 0.U
      )
}

trait HasLogicAnalyzerModule extends HasDspChainParameters with HasDecoupledSCR {
  def scrfile: SCRFile
  def dataWidthWords: Int
  def wordWidth: Int

  lazy val logicAnalyzer = {
    val logicAnalyzer    = Module(new LogicAnalyzer(
                                    dataWidthWords*wordWidth, 1,
                                    logicAnalyzerSamples,
                                    logicAnalyzerUseCombinationalTrigger)
                                  )
    scrfile.status("logicAnalyzerState")      :=
      logicAnalyzer.io.status.state
    scrfile.status("logicAnalyzerNumSampled") :=
      logicAnalyzer.io.status.numSampled
    scrfile.status("logicAnalyzerOverflow")   :=
      logicAnalyzer.io.status.overflow

    logicAnalyzer.io.memory.reqAddr :=
      scrfile.control("logicAnalyzerWriteAddr")

    val logicAnalyzerWriteData = (0 until dataWidthWords).map(i =>
        scrfile.status(s"logicAnalyzerWriteData_$i")
        )

    logicAnalyzerWriteData.zipWithIndex.foreach { case(la, idx) =>
      val width = la.getWidth
      la := logicAnalyzer.io.memory.respData(0)(width * (idx + 1) - 1, width * idx)
    }

    logicAnalyzer.io.control.bits.validBypass :=
      scrfile.control("logicAnalyzerValidBypass")
    logicAnalyzer.io.control.bits.triggerMode :=
      scrfile.control("logicAnalyzerTriggerMode")
    logicAnalyzer.io.control.bits.numSamples :=
      scrfile.control("logicAnalyzerNumSamples")
    logicAnalyzer.io.control.bits.arm :=
      scrfile.control("logicAnalyzerArm")
    logicAnalyzer.io.control.bits.abort :=
      scrfile.control("logicAnalyzerAbort")

    decoupledHelper(
      logicAnalyzer.io.control.valid,
      logicAnalyzer.io.control.ready,
      scrfile.control("logicAnalyzerControlEnable") =/= 0.U,
      scrfile.status("logicAnalyzerControlFinished"))


    logicAnalyzer.io.signal.valid := false.B
    logicAnalyzer.io.signal.bits  := 0.U

    logicAnalyzer
  }
  lazy val logicAnalyzerSelects = (0 until totalLABlocks).map(i =>
        scrfile.control("logicAnalyzerSelect") === i.U &&
        scrfile.control("logicAnalyzerEnable") =/= 0.U
        )
}

class DspChainIO()(implicit val p: Parameters) extends Bundle {
  val control_axi = Flipped(new NastiIO())
  val data_axi    = Flipped(new NastiIO())
}

case object DspChainAXI4SInputWidth extends Field[Int]

trait AXI4SInputIO {
  def p: Parameters
  def streamWidth = p(DspChainAXI4SInputWidth)
  val stream_in = Flipped(ValidWithSync(UInt(streamWidth.W)))
}

/*trait AXI4SInput { this: DspChain =>
  def module = new DspChainModule(this,
    Some(new DspChainIO with AXI4SInputIO),
    override_clock, override_reset)(p)
}*/

trait AXI4SInputModule {
  def io: DspChainIO with AXI4SInputIO
  def streamIn = io.stream_in
}

abstract class DspChain()
  (implicit val p: Parameters) extends LazyModule with HasDspChainParameters
  with HasSCRBuilder with HasPatternGenerator with HasLogicAnalyzer {
  def module: DspChainModule
  def scrName = p(DspChainId)
  var ctrlBaseAddr: () => Long = () => 0L
  var dataBaseAddr: () => Long = () => 0L

  require(blocks.length > 0)

  val lazyMods = blocks.map(b => {
    val modParams = p.alterPartial({
      case DspBlockId => b._2
    })
    val mod = LazyModule(b._1(modParams))
    mod
  })
  val lazySams = lazyMods.zip(blocksUseSAM).zip(blocksSAMConfigs).map({ case((mod, useSam), samConfig) =>
    val samWidth = try {
      p(GenKey(mod.id)).lanesOut * p(GenKey(mod.id)).genOut.getWidth
    } catch {
      case e: ParameterUndefinedException =>
        p(DspBlockKey(mod.id)).outputWidth
    }
    val samName = mod.id + ":sam"
    val samParams = p.alterPartial({
      case SAMKey(_id) if _id == samName => samConfig.getOrElse(p(DefaultSAMKey))
      case DspBlockId => samName
      case DspBlockKey(_id) if _id == samName => DspBlockParameters(samWidth, samWidth)
      case IPXactParameters(_id) if _id == samName => {
        val parameterMap = scala.collection.mutable.HashMap[String, String]()

        // Conjure up some IPXACT synthsized parameters.
        val config = samConfig.getOrElse(p(DefaultSAMKey))
        parameterMap ++= List(("InputTotalBits", samWidth.toString))
        parameterMap ++= List(("MemoryDepth", config.bufferDepth.toString))

        parameterMap
      }
    })
    if (useSam) {
      println("Creating SAM:")
      println(s"\tBlock ID: ${mod.id}")
      try {
        println(s"\tLanes Out: ${p(GenKey(mod.id)).lanesOut}")
        println(s"\tgenOut Width: ${p(GenKey(mod.id)).genOut.getWidth}")
      } catch {
        case e: ParameterUndefinedException =>
      }
      println(s"\tSAM Width: ${samWidth}")
      val lazySam = LazyModule( new SAMWrapper()(samParams) )
      Some(lazySam)
    } else {
      None
    }
  })
  val flattenedLazySams = lazySams.flatten.toSeq
  val scrSizeBytes = (scrbuilder.controlNames.length +
                      scrbuilder.statusNames.length) * (scrbuilder.scrDataBits + 7) /8
  val ctrlAddrMapEntries =
    lazyMods.map(_.addrMapEntry) ++
    flattenedLazySams.map(_.addrMapEntry) ++
    Seq(
      AddrMapEntry(s"chain", MemSize(scrSizeBytes, MemAttr(AddrMapProt.RWX)))
    )
  val dataAddrMapEntries =
    lazySams.zipWithIndex.map({
      case (Some(sam), idx) =>
        Some(
          AddrMapEntry(s"${lazyMods(idx).id}:sam:data", MemSize(sam.config.memDepth, MemAttr(AddrMapProt.RWX)))
        )
      case (None, _) => None
    }).flatten.toSeq

  def makeAddrMap(entries: Seq[AddrMapEntry], start: Long = 0L) = {
    val maxSize = entries.map(_.region.size).max
    // round up to nearest power of 2
    val newSize = 1L << util.log2Ceil(maxSize)
    val newEntries = entries.map(e =>
      AddrMapEntry(e.name, e.region match {
        case s: MemSize =>
          s.copy(size = newSize)
        case r: MemRange =>
          r.copy(size = newSize)
        case _ => throw dsptools.DspException("Invalid type of region")
      })
    )
    new AddrMap(newEntries, start=start)
  }
  def getMemSize(entries: Seq[AddrMapEntry], start: Long = 0L) = {
    val addrMap = makeAddrMap(entries, start)
    MemSize(addrMap.size, addrMap.attr)
  }

  val ctrlMemSize = getMemSize(ctrlAddrMapEntries)
  val dataMemSize = getMemSize(dataAddrMapEntries)

  /*ctrlAddrMapEntries.foreach {e =>
    println(s"Entry ${e.name} had size ${e.region.size}")
  }*/

  // the outer lazy module gets its base addresses set by the pbus
  // make an address map now that we have the correct base address
  def ctrlAddrs = makeAddrMap(ctrlAddrMapEntries, ctrlBaseAddr())
  def dataAddrs = makeAddrMap(dataAddrMapEntries, dataBaseAddr())

  // assign base addresses to scrs and sams
  (lazyMods ++ flattenedLazySams).zipWithIndex.foreach{ case (mod, idx) =>
    mod.setBaseAddr(() => {
      val addr = ctrlAddrs.entries(idx)
      addr.region.start
    })
  }

  flattenedLazySams.zipWithIndex.foreach { case(sam, idx) =>
    sam.setDataBaseAddr(() => {
      val addr = dataAddrs.entries(idx)
      addr.region.start
    })
  }
}

class DspChainWithAXI4SInput(
  b: => Option[DspChainIO with AXI4SInputIO] = None,
  override_clock: Option[Clock]=None,
  override_reset: Option[Bool]=None)(implicit p: Parameters) extends
    DspChain() {
  lazy val module = new DspChainWithAXI4SInputModule(this, b, override_clock, override_reset)
}

class DspChainWithAXI4SInputModule(
  outer: DspChain,
  b: => Option[DspChainIO with AXI4SInputIO] = None,
  override_clock: Option[Clock]=None,
  override_reset: Option[Bool]=None)(implicit p: Parameters)
  extends DspChainModule(outer, b, override_clock, override_reset)
    with AXI4SInputModule {
  override lazy val io = b.getOrElse(new DspChainIO with AXI4SInputIO)
}

abstract class DspChainModule(
  val outer: DspChain,
  b: => Option[DspChainIO] = None,
  override_clock: Option[Clock]=None,
  override_reset: Option[Bool]=None)(implicit val p: Parameters)
  extends LazyModuleImp(outer, override_clock, override_reset)
    with HasDspChainParameters
    with HasPatternGeneratorModule with HasLogicAnalyzerModule
    with WithChainHeaderWriter {
  // This gets connected to the input of the first block
  // Different traits that implement streamIn should be mixed in
  // to feed data into the first block
  def streamIn: ValidWithSync[UInt]

  val tlid = p(TLId)
  val tlkey = p(TLKey(tlid))
  require(tlkey.dataBitsPerBeat == 64,
    s"SCR File in DspChain requires 64-bit data bits per beat, got ${tlkey.dataBitsPerBeat}")
  require(tlkey.dataBeats > 1,
    s"There's a problem with the TL converters if dataBeats is 1")

  def ctrlBaseAddr       = outer.ctrlBaseAddr()
  def dataBaseAddr       = outer.dataBaseAddr()
  val ctrlAddrMapEntries = outer.ctrlAddrMapEntries
  val dataAddrMapEntries = outer.dataAddrMapEntries
  val ctrlAddrs          = outer.ctrlAddrs
  val dataAddrs          = outer.dataAddrs
  val lazyMods           = outer.lazyMods
  val lazySams           = outer.lazySams
  val flattenedLazySams  = outer.flattenedLazySams
  val makeAddrMap        = outer.makeAddrMap _

  // the scrfile for the chain is the last entry in the ctrl address map
  // construct it and connect it to a AXI <-> TL converter
  val scrBaseAddr = ctrlAddrs.entries.last.region.start
  val scrfile = outer.scrbuilder.generate(scrBaseAddr)
  val scrfile_tl2axi = Module(new TileLinkIONastiIOConverter())
  scrfile_tl2axi.io.tl <> scrfile.io.tl
  IPXactComponents._ipxactComponents += DspIPXact.makeSCRComponent(scrBaseAddr, outer.scrName, scrfile_tl2axi.name)(p)

  // instantiate modules
  val modules = lazyMods.map(mod => mod.module)
  modules.map(m => {
    IPXactComponents._ipxactComponents += DspIPXact.makeDspBlockComponent(m.baseAddr, m.uuid, m.name)(m.p)
  })
  val mod_ios = modules.map(_.io)
  lazy val io = IO(b.getOrElse(new DspChainIO))

  // make sure the pattern generator and logic analyzer are instantitated
  patternGenerator
  logicAnalyzer


  val maxDataWidth = mod_ios.map(i =>
      math.max(i.in.bits.getWidth, i.out.bits.getWidth)
  ).reduce(math.max(_, _))

  val lastDataWidth = mod_ios.last.out.bits.getWidth

  for (i <- 0 until mod_ios.length - 1) {
    require(mod_ios(i+1).in.bits.getWidth == mod_ios(i).out.bits.getWidth,
      "Connecting modules with different width IOs")
    mod_ios(i + 1).in <> mod_ios(i).out
  }

  // instantiate sams
  val sams = lazySams.map(_ match {
    case Some(ls) =>
      val sam = ls.module
      Some(sam)
    case None =>
      None
  })
  val flattenedSams = sams.flatten.toSeq
  flattenedSams.map(s =>
    IPXactComponents._ipxactComponents += DspIPXact.makeSAMComponent(s.baseAddr, s.dataBaseAddr, s.config.memDepth, s.uuid, s.name)(s.p)
    )

  // unless pattern generator asserted, every module connects to the next
  mod_ios.foldLeft(streamIn) { case(out, mod) =>
    mod.in <> out
    mod.out
  }

  var currentPatternGen = 0
  var currentLogicAnalyzer = 0
  for (i <- 0 until mod_ios.length) {
    if (blocksUsePG(i)) {
      when (patternGeneratorSelects(currentPatternGen)) {
        mod_ios(i).in.bits  := patternGenerator.io.signal.bits
        mod_ios(i).in.valid := patternGenerator.io.signal.valid
      }
      currentPatternGen += 1
    }
    if (blocksUseLA(i)) {
      when (logicAnalyzerSelects(currentLogicAnalyzer)) {
        logicAnalyzer.io.signal.valid := mod_ios(i).out.valid
        logicAnalyzer.io.signal.bits  := mod_ios(i).out.bits
      }
      currentLogicAnalyzer += 1
    }
  }

  // connect output of each module to appropriate SAM
  modules.zip(sams).foreach {
    case (mod, Some(sam)) =>
      sam.io.in := mod.io.out
    case _ =>
  }

  val control_axis = (modules ++ flattenedSams).map(_.io.axi) :+ scrfile_tl2axi.io.nasti

  val ctrlOutPorts = control_axis.length
  val dataOutPorts = totalSAMBlocks

  val inPorts = 1
  val ctrlXbarParams = p.alterPartial({
    case TLKey("XBar") => p(TLKey("MCtoEdge")).copy(
      overrideDataBitsPerBeat = Some(64),
      nCachingClients = 0,
      nCachelessClients = ctrlOutPorts,
      maxClientXacts = 4,
      maxClientsPerPort = inPorts)
    case TLId => "XBar"
    case GlobalAddrMap => {
      val memSize = 0x1000L
      ctrlAddrs
    }
    case XBarQueueDepth => 2
    case ExtMemSize => 0x1000L
    case InPorts => inPorts
    case OutPorts => ctrlOutPorts
    case DspBlockId => s"$id:ctrl"
  })
  val dataXbarParams = p.alterPartial({
    case TLKey("XBar") => p(TLKey("MCtoEdge")).copy(
      overrideDataBitsPerBeat = Some(64),
      nCachingClients = 0,
      nCachelessClients = dataOutPorts,
      maxClientXacts = 4,
      maxClientsPerPort = inPorts)
    case TLId => "XBar"
    case GlobalAddrMap => {
      val memSize = 0x1000L
      dataAddrs
    }
    case XBarQueueDepth => 2
    case ExtMemSize => 0x1000L
    case InPorts => inPorts
    case OutPorts => dataOutPorts
    case DspBlockId => s"$id:data"
  })

  DspChain._addrMaps += (s"$id:data" -> dataAddrs)
  DspChain._addrMaps += (s"$id:ctrl" -> ctrlAddrs)
  println(s"In the address map list we have keys ${DspChain.addrMapIds()}")
  for (key <- DspChain.addrMapIds()) {
    println(s"AddrMap($key) =\n${DspChain.addrMap(key).entries.foreach(x => println(x.toString))}")
  }
  println(s"We have ${control_axis.length} OutPorts on the XBar")
  println(s"We have ${ctrlXbarParams(GlobalAddrMap).numSlaves} ctrl slaves in the AddrMap")
  println(s"We have ${dataXbarParams(GlobalAddrMap).numSlaves} data slaves in the AddrMap")


  val ctrlXbar = Module(new NastiXBar(ctrlXbarParams))
  val dataXbar = Module(new NastiXBar(dataXbarParams))
  IPXactComponents._ipxactComponents += DspIPXact.makeXbarComponent(ctrlXbarParams, ctrlXbar.name)
  IPXactComponents._ipxactComponents += DspIPXact.makeXbarComponent(dataXbarParams, dataXbar.name)

  ctrlXbar.io.in(0) <> io.control_axi
  ctrlXbar.io.out.zip(control_axis).foreach{ case (xbar_axi, control_axi) => xbar_axi <> control_axi }

  dataXbar.io.in(0) <> io.data_axi
  dataXbar.io.out.zip(flattenedSams).foreach{ case (xbar_axi, sam) => xbar_axi <> sam.io.asInstanceOf[SAMWrapperIO].axi_out }

  annotateHeader
}
