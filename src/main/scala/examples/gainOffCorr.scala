//// See LICENSE for license details.
//
package dsptools.examples

import chisel3.core._
import chisel3.{Bundle, Module}
import dsptools.DspTester
import org.scalatest.{Matchers, FlatSpec}

//Simple implementation does the following
//   1.Input value can be either real/imag
//   2.Gain and offset either real/imag
//   3.Assuming the number of input sources = number of lanes for now
//   4.Assuming that the memory interface for gain and offset values will be done at a higher level

class gainOffCorr[T<:Data:Ring](genIn: => T,genGain: => T,genOff: => T,genOut: => T, numLanes: Int) extends Module {
    val io = new Bundle {
       val inputVal =  Vec(numLames, genIn.asInput)
       val gainCorr =  Vec(numLanes, genGain.asInput)
       val offsetCorr = Vec(numLanes, genOff.asInput)
       val outputVal = Vec(numLanes, genOut.asOutput) 
    }
   
    val inputGainCorr = io.inputVal.zip(io.gainCorr).map{case (in, gain) => in*gain } 
    io.outputVal = inputGainCorr.zip(io.offsetCorr).map{case (inGainCorr, offset) => inGainCorr + offset }
}
