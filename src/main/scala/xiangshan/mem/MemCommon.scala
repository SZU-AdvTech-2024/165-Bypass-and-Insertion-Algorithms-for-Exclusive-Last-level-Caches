/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.mem


import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import utility._
import xiangshan.backend.rob.RobPtr
import xiangshan.cache._
import xiangshan.backend.fu.FenceToSbuffer
import xiangshan.cache.wpu.ReplayCarry
import xiangshan.mem.prefetch.PrefetchReqBundle

object genWmask {
  def apply(addr: UInt, sizeEncode: UInt): UInt = {
    (LookupTree(sizeEncode, List(
      "b00".U -> 0x1.U, //0001 << addr(2:0)
      "b01".U -> 0x3.U, //0011
      "b10".U -> 0xf.U, //1111
      "b11".U -> 0xff.U //11111111
    )) << addr(2, 0)).asUInt
  }
}

object genVWmask {
  def apply(addr: UInt, sizeEncode: UInt): UInt = {
    (LookupTree(sizeEncode, List(
      "b00".U -> 0x1.U, //0001 << addr(2:0)
      "b01".U -> 0x3.U, //0011
      "b10".U -> 0xf.U, //1111
      "b11".U -> 0xff.U //11111111
    )) << addr(3, 0)).asUInt
  }
}

object genWdata {
  def apply(data: UInt, sizeEncode: UInt): UInt = {
    LookupTree(sizeEncode, List(
      "b00".U -> Fill(16, data(7, 0)),
      "b01".U -> Fill(8, data(15, 0)),
      "b10".U -> Fill(4, data(31, 0)),
      "b11".U -> Fill(2, data(63,0))
    ))
  }
}

object shiftDataToLow {
  def apply(addr: UInt,data : UInt): UInt = {
    Mux(addr(3), (data >> 64).asUInt,data)
  }
}
object shiftMaskToLow {
  def apply(addr: UInt,mask: UInt): UInt = {
    Mux(addr(3),(mask >> 8).asUInt,mask)
  }
}

class LsPipelineBundle(implicit p: Parameters) extends XSBundleWithMicroOp with HasDCacheParameters{
  val vaddr = UInt(VAddrBits.W)
  val paddr = UInt(PAddrBits.W)
  // val func = UInt(6.W)
  val mask = UInt((VLEN/8).W)
  val data = UInt((VLEN+1).W)
  val wlineflag = Bool() // store write the whole cache line

  val miss = Bool()
  val tlbMiss = Bool()
  val ptwBack = Bool()
  val mmio = Bool()
  val atomic = Bool()
  val rsIdx = UInt(log2Up(IssQueSize).W)

  val forwardMask = Vec(VLEN/8, Bool())
  val forwardData = Vec(VLEN/8, UInt(8.W))

  // prefetch
  val isPrefetch = Bool()
  val isHWPrefetch = Bool()
  def isSWPrefetch = isPrefetch && !isHWPrefetch

  // For debug usage
  val isFirstIssue = Bool()
  val hasROBEntry = Bool()

  // For load replay
  val isLoadReplay = Bool()
  val isFastPath = Bool()
  val isFastReplay = Bool()
  val replayCarry = new ReplayCarry(nWays)

  // For dcache miss load
  val mshrid = UInt(log2Up(cfg.nMissEntries).W)
  val handledByMSHR = Bool()
  val replacementUpdated = Bool()
  val missDbUpdated = Bool()

  val forward_tlDchannel = Bool()
  val dcacheRequireReplay = Bool()
  val delayedLoadError = Bool()
  val lateKill = Bool()
  val feedbacked = Bool()

  // loadQueueReplay index.
  val schedIndex = UInt(log2Up(LoadQueueReplaySize).W)
}

class LdPrefetchTrainBundle(implicit p: Parameters) extends LsPipelineBundle {
  val meta_prefetch = UInt(L1PfSourceBits.W)
  val meta_access = Bool()

  def fromLsPipelineBundle(input: LsPipelineBundle, latch: Boolean = false) = {
    if (latch) vaddr := RegNext(input.vaddr) else vaddr := input.vaddr
    if (latch) paddr := RegNext(input.paddr) else paddr := input.paddr
    if (latch) mask := RegNext(input.mask) else mask := input.mask
    if (latch) data := RegNext(input.data) else data := input.data
    if (latch) uop := RegNext(input.uop) else uop := input.uop
    if (latch) wlineflag := RegNext(input.wlineflag) else wlineflag := input.wlineflag
    if (latch) miss := RegNext(input.miss) else miss := input.miss
    if (latch) tlbMiss := RegNext(input.tlbMiss) else tlbMiss := input.tlbMiss
    if (latch) ptwBack := RegNext(input.ptwBack) else ptwBack := input.ptwBack
    if (latch) mmio := RegNext(input.mmio) else mmio := input.mmio
    if (latch) rsIdx := RegNext(input.rsIdx) else rsIdx := input.rsIdx
    if (latch) forwardMask := RegNext(input.forwardMask) else forwardMask := input.forwardMask
    if (latch) forwardData := RegNext(input.forwardData) else forwardData := input.forwardData
    if (latch) isPrefetch := RegNext(input.isPrefetch) else isPrefetch := input.isPrefetch
    if (latch) isHWPrefetch := RegNext(input.isHWPrefetch) else isHWPrefetch := input.isHWPrefetch
    if (latch) isFirstIssue := RegNext(input.isFirstIssue) else isFirstIssue := input.isFirstIssue
    if (latch) hasROBEntry := RegNext(input.hasROBEntry) else hasROBEntry := input.hasROBEntry
    if (latch) dcacheRequireReplay := RegNext(input.dcacheRequireReplay) else dcacheRequireReplay := input.dcacheRequireReplay
    if (latch) schedIndex := RegNext(input.schedIndex) else schedIndex := input.schedIndex

    meta_prefetch := DontCare
    meta_access := DontCare
    forward_tlDchannel := DontCare
    mshrid := DontCare
    replayCarry := DontCare
    atomic := DontCare
    isLoadReplay := DontCare
    isFastPath := DontCare
    isFastReplay := DontCare
    handledByMSHR := DontCare
    replacementUpdated := DontCare
    missDbUpdated := DontCare
    delayedLoadError := DontCare
    lateKill := DontCare
    feedbacked := DontCare
  }

  def asPrefetchReqBundle(): PrefetchReqBundle = {
    val res = Wire(new PrefetchReqBundle)
    res.vaddr := this.vaddr
    res.paddr := this.paddr
    res.pc    := this.uop.cf.pc

    res
  }
}

class StPrefetchTrainBundle(implicit p: Parameters) extends LdPrefetchTrainBundle {}

class LqWriteBundle(implicit p: Parameters) extends LsPipelineBundle {
  // load inst replay informations
  val rep_info = new LoadToLsqReplayIO
  // queue entry data, except flag bits, will be updated if writeQueue is true,
  // valid bit in LqWriteBundle will be ignored
  val data_wen_dup = Vec(6, Bool()) // dirty reg dup


  def fromLsPipelineBundle(input: LsPipelineBundle, latch: Boolean = false) = {
    if(latch) vaddr := RegNext(input.vaddr) else vaddr := input.vaddr
    if(latch) paddr := RegNext(input.paddr) else paddr := input.paddr
    if(latch) mask := RegNext(input.mask) else mask := input.mask
    if(latch) data := RegNext(input.data) else data := input.data
    if(latch) uop := RegNext(input.uop) else uop := input.uop
    if(latch) wlineflag := RegNext(input.wlineflag) else wlineflag := input.wlineflag
    if(latch) miss := RegNext(input.miss) else miss := input.miss
    if(latch) tlbMiss := RegNext(input.tlbMiss) else tlbMiss := input.tlbMiss
    if(latch) ptwBack := RegNext(input.ptwBack) else ptwBack := input.ptwBack
    if(latch) mmio := RegNext(input.mmio) else mmio := input.mmio
    if(latch) atomic := RegNext(input.atomic) else atomic := input.atomic
    if(latch) rsIdx := RegNext(input.rsIdx) else rsIdx := input.rsIdx
    if(latch) forwardMask := RegNext(input.forwardMask) else forwardMask := input.forwardMask
    if(latch) forwardData := RegNext(input.forwardData) else forwardData := input.forwardData
    if(latch) isPrefetch := RegNext(input.isPrefetch) else isPrefetch := input.isPrefetch
    if(latch) isHWPrefetch := RegNext(input.isHWPrefetch) else isHWPrefetch := input.isHWPrefetch
    if(latch) isFirstIssue := RegNext(input.isFirstIssue) else isFirstIssue := input.isFirstIssue
    if(latch) hasROBEntry := RegNext(input.hasROBEntry) else hasROBEntry := input.hasROBEntry
    if(latch) isLoadReplay := RegNext(input.isLoadReplay) else isLoadReplay := input.isLoadReplay
    if(latch) isFastPath := RegNext(input.isFastPath) else isFastPath := input.isFastPath
    if(latch) isFastReplay := RegNext(input.isFastReplay) else isFastReplay := input.isFastReplay
    if(latch) mshrid := RegNext(input.mshrid) else mshrid := input.mshrid
    if(latch) forward_tlDchannel := RegNext(input.forward_tlDchannel) else forward_tlDchannel := input.forward_tlDchannel
    if(latch) replayCarry := RegNext(input.replayCarry) else replayCarry := input.replayCarry
    if(latch) dcacheRequireReplay := RegNext(input.dcacheRequireReplay) else dcacheRequireReplay := input.dcacheRequireReplay
    if(latch) schedIndex := RegNext(input.schedIndex) else schedIndex := input.schedIndex
    if(latch) handledByMSHR := RegNext(input.handledByMSHR) else handledByMSHR := input.handledByMSHR
    if(latch) replacementUpdated := RegNext(input.replacementUpdated) else replacementUpdated := input.replacementUpdated
    if(latch) missDbUpdated := RegNext(input.missDbUpdated) else missDbUpdated := input.missDbUpdated
    if(latch) delayedLoadError := RegNext(input.delayedLoadError) else delayedLoadError := input.delayedLoadError
    if(latch) lateKill := RegNext(input.lateKill) else lateKill := input.lateKill
    if(latch) feedbacked := RegNext(input.feedbacked) else feedbacked := input.feedbacked

    rep_info := DontCare
    data_wen_dup := DontCare
  }
}

class LoadForwardQueryIO(implicit p: Parameters) extends XSBundleWithMicroOp {
  val vaddr = Output(UInt(VAddrBits.W))
  val paddr = Output(UInt(PAddrBits.W))
  val mask = Output(UInt((VLEN/8).W))
  override val uop = Output(new MicroOp) // for replay
  val pc = Output(UInt(VAddrBits.W)) //for debug
  val valid = Output(Bool())

  val forwardMaskFast = Input(Vec((VLEN/8), Bool())) // resp to load_s1
  val forwardMask = Input(Vec((VLEN/8), Bool())) // resp to load_s2
  val forwardData = Input(Vec((VLEN/8), UInt(8.W))) // resp to load_s2

  // val lqIdx = Output(UInt(LoadQueueIdxWidth.W))
  val sqIdx = Output(new SqPtr)

  // dataInvalid suggests store to load forward found forward should happen,
  // but data is not available for now. If dataInvalid, load inst should
  // be replayed from RS. Feedback type should be RSFeedbackType.dataInvalid
  val dataInvalid = Input(Bool()) // Addr match, but data is not valid for now

  // matchInvalid suggests in store to load forward logic, paddr cam result does
  // to equal to vaddr cam result. If matchInvalid, a microarchitectural exception
  // should be raised to flush SQ and committed sbuffer.
  val matchInvalid = Input(Bool()) // resp to load_s2

  // addrInvalid suggests store to load forward found forward should happen,
  // but address (SSID) is not available for now. If addrInvalid, load inst should
  // be replayed from RS. Feedback type should be RSFeedbackType.addrInvalid
  val addrInvalid = Input(Bool())
}

// LoadForwardQueryIO used in load pipeline
//
// Difference between PipeLoadForwardQueryIO and LoadForwardQueryIO:
// PipeIO use predecoded sqIdxMask for better forward timing
class PipeLoadForwardQueryIO(implicit p: Parameters) extends LoadForwardQueryIO {
  // val sqIdx = Output(new SqPtr) // for debug, should not be used in pipeline for timing reasons
  // sqIdxMask is calcuated in earlier stage for better timing
  val sqIdxMask = Output(UInt(StoreQueueSize.W))

  // dataInvalid: addr match, but data is not valid for now
  val dataInvalidFast = Input(Bool()) // resp to load_s1
  val dataInvalidSqIdx = Input(new SqPtr) // resp to load_s2, sqIdx

  // addrInvalidFast: addr not match
  val addrInvalidFast = Input(Bool())
  val addrInvalidSqIdx = Input(new SqPtr) // resp to load_s2, sqIdx
}

// Query load queue for ld-ld violation
//
// Req should be send in load_s1
// Resp will be generated 1 cycle later
//
// Note that query req may be !ready, as dcache is releasing a block
// If it happens, a replay from rs is needed.
class LoadNukeQueryIO(implicit p: Parameters) extends XSBundle {
  // pre allocation
  val s1_prealloc = Output(Bool())
  val s1_lqIdx = Output(new LqPtr)
  val s1_sqIdx = Output(new SqPtr)
  val s1_robIdx = Output(new RobPtr)
  val s1_nack = Input(Bool())

  // allocation
  val s2_alloc = Output(Bool())
  val s2_uop = Output(new MicroOp)
  val s2_mask = Output(UInt((VLEN/8).W))
  val s2_paddr = Output(UInt(PAddrBits.W))
  val s2_dataInvalid = Output(Bool())

  // ld-ld violation check success, replay from fetch.
  val s3_nuke = Input(Bool())

  // revoke RAR/RAW allocation
  val s3_revoke = Output(Bool())
}

class NukeQueryIO(implicit p: Parameters) extends XSBundle {
  val rar = new LoadNukeQueryIO
  val raw = new LoadNukeQueryIO
}

class StoreNukeQueryIO(implicit p: Parameters) extends XSBundle {
  val s2_valid = Output(Bool())
  //  robIdx: Requestor's (a store instruction) rob index for match logic.
  val s2_robIdx = Output(new RobPtr)
  //  paddr: requestor's (a store instruction) physical address for match logic.
  val s2_paddr  = Output(UInt(PAddrBits.W))
  //  mask: requestor's (a store instruction) data width mask for match logic.
  val s2_mask = Output(UInt((VLEN/8).W))
  //
  val s4_nuke = Input(Bool())
}

// Store byte valid mask write bundle
//
// Store byte valid mask write to SQ takes 2 cycles
class StoreMaskBundle(implicit p: Parameters) extends XSBundle {
  val sqIdx = new SqPtr
  val mask = UInt((VLEN/8).W)
}

class LoadDataFromDcacheBundle(implicit p: Parameters) extends DCacheBundle {
  // old dcache: optimize data sram read fanout
  // val bankedDcacheData = Vec(DCacheBanks, UInt(64.W))
  // val bank_oh = UInt(DCacheBanks.W)

  // new dcache
  val respDcacheData = UInt(VLEN.W)
  val forwardMask = Vec(VLEN/8, Bool())
  val forwardData = Vec(VLEN/8, UInt(8.W))
  val uop = new MicroOp // for data selection, only fwen and fuOpType are used
  val addrOffset = UInt(4.W) // for data selection

  // forward tilelink D channel
  val forward_D = Bool()
  val forwardData_D = Vec(VLEN/8, UInt(8.W))

  // forward mshr data
  val forward_mshr = Bool()
  val forwardData_mshr = Vec(VLEN/8, UInt(8.W))

  val forward_result_valid = Bool()

  def dcacheData(): UInt = {
    // old dcache
    // val dcache_data = Mux1H(bank_oh, bankedDcacheData)
    // new dcache
    val dcache_data = respDcacheData
    val use_D = forward_D && forward_result_valid
    val use_mshr = forward_mshr && forward_result_valid
    Mux(use_D, forwardData_D.asUInt, Mux(use_mshr, forwardData_mshr.asUInt, dcache_data))
  }

  def mergedData(): UInt = {
    val rdataVec = VecInit((0 until VLEN / 8).map(j =>
      Mux(forwardMask(j), forwardData(j), dcacheData()(8*(j+1)-1, 8*j))
    ))
    rdataVec.asUInt
  }
}

// Load writeback data from load queue (refill)
class LoadDataFromLQBundle(implicit p: Parameters) extends XSBundle {
  val lqData = UInt(64.W) // load queue has merged data
  val uop = new MicroOp // for data selection, only fwen and fuOpType are used
  val addrOffset = UInt(3.W) // for data selection

  def mergedData(): UInt = {
    lqData
  }
}

// Bundle for load / store wait waking up
class MemWaitUpdateReq(implicit p: Parameters) extends XSBundle {
  val staIssue = Vec(exuParameters.StuCnt, ValidIO(new ExuInput))
  val stdIssue = Vec(exuParameters.StuCnt, ValidIO(new ExuInput))
}

object AddPipelineReg {
  class PipelineRegModule[T <: Data](gen: T) extends Module {
    val io = IO(new Bundle() {
      val in = Flipped(DecoupledIO(gen.cloneType))
      val out = DecoupledIO(gen.cloneType)
      val isFlush = Input(Bool())
    })

    val valid = RegInit(false.B)
    valid.suggestName("pipeline_reg_valid")
    when (io.out.fire) { valid := false.B }
    when (io.in.fire) { valid := true.B }
    when (io.isFlush) { valid := false.B }

    io.in.ready := !valid || io.out.ready
    io.out.bits := RegEnable(io.in.bits, io.in.fire)
    io.out.valid := valid //&& !isFlush
  }

  def apply[T <: Data]
  (left: DecoupledIO[T], right: DecoupledIO[T], isFlush: Bool,
   moduleName: Option[String] = None
  ){
    val pipelineReg = Module(new PipelineRegModule[T](left.bits.cloneType))
    if(moduleName.nonEmpty) pipelineReg.suggestName(moduleName.get)
    pipelineReg.io.in <> left
    right <> pipelineReg.io.out
    pipelineReg.io.isFlush := isFlush
  }
}
