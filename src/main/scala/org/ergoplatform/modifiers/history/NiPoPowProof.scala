package org.ergoplatform.modifiers.history

import com.google.common.primitives.{Bytes, Ints}
import org.ergoplatform.mining.difficulty.RequiredDifficulty
import org.ergoplatform.modifiers.ErgoPersistentModifier
import org.ergoplatform.settings.Algos
import scorex.core.ModifierTypeId
import scorex.core.serialization.Serializer
import scorex.core.validation.ModifierValidator
import scorex.util.ModifierId

import scala.util.Try

case class NiPoPowProof(m: Int,
                        k: Int,
                        prefix: Seq[Header],
                        suffix: Seq[Header],
                        sizeOpt: Option[Int] = None)
  extends ErgoPersistentModifier with ModifierValidator {

  import NiPoPowProof._

  override type M = NiPoPowProof

  override val modifierTypeId: ModifierTypeId = TypeId

  override def serializedId: Array[Byte] = Algos.hash(bytes)

  override def serializer: Serializer[NiPoPowProof] = NiPoPowProofSerializer

  override def parentId: ModifierId = prefix.head.id

  def headersOfLevel(l: Int): Seq[Header] = prefix.filter(maxLevelOf(_) >= l)

  def validate: Try[Unit] = {
    failFast
      .demand(suffix.lengthCompare(k) == 0, "Invalid suffix length")
      .demand(prefix.tail.groupBy(maxLevelOf).forall(_._2.lengthCompare(m) == 0), "Invalid prefix length")
      .demand(prefix.tail.forall(_.interlinks.headOption.contains(prefix.head.id)), "Chain is not anchored")
      .result
      .toTry
  }

  def isBetterThan(that: NiPoPowProof): Boolean = {
    val (thisDivergingChain, thatDivergingChain) = lowestCommonAncestor(prefix, that.prefix)
      .map(h => prefix.filter(_.height > h.height) -> that.prefix.filter(_.height > h.height))
      .getOrElse(prefix -> that.prefix)
    bestArg(thisDivergingChain)(m) > bestArg(thatDivergingChain)(m)
  }

}

object NiPoPowProof {

  val TypeId: ModifierTypeId = ModifierTypeId @@ (110: Byte)

  def maxLevelOf(header: Header): Int = {
    if (!header.isGenesis) {
      def log2(x: Double) = math.log(x) / math.log(2)
      val requiredTarget = org.ergoplatform.mining.q / RequiredDifficulty.decodeCompactBits(header.nBits)
      val realTarget = header.powSolution.d
      val level = log2(requiredTarget.doubleValue) - log2(realTarget.doubleValue)
      level.toInt
    } else {
      Int.MaxValue
    }
  }

  def bestArg(chain: Seq[Header])(m: Int): Int = {
    def loop(level: Int, acc: Seq[(Int, Int)] = Seq.empty): Seq[(Int, Int)] = {
      if (level == 0) {
        loop(level + 1, acc :+ (0, chain.size)) // Supposing each header is at least of level 0.
      } else {
        val args = chain.filter(maxLevelOf(_) >= level)
        if (args.lengthCompare(m) >= 0) loop(level + 1, acc :+ (level, args.size)) else acc
      }
    }
    loop(level = 0).map { case (lvl, size) =>
      math.pow(2, lvl) * size // 2^µ * |C↑µ|
    }.max.toInt
  }

  def lowestCommonAncestor(leftChain: Seq[Header], rightChain: Seq[Header]): Option[Header] = {
    def lcaIndex(startIdx: Int): Int = {
      if (leftChain.lengthCompare(startIdx) >= 0 && rightChain.lengthCompare(startIdx) >= 0 &&
        leftChain(startIdx) == rightChain(startIdx)) {
        lcaIndex(startIdx + 1)
      } else {
        startIdx - 1
      }
    }
    if (leftChain.headOption.exists(h => rightChain.headOption.contains(h))) Some(leftChain(lcaIndex(1))) else None
  }

  def updateInterlinks(header: Header): Seq[ModifierId] = {
    if (!header.isGenesis) {
      val genesis = header.interlinks.head
      val tail = header.interlinks.tail
      val prevLevel = maxLevelOf(header)
      if (prevLevel > 0) {
        (genesis +: tail.dropRight(prevLevel)) ++ Seq.fill(prevLevel)(header.id)
      } else {
        header.interlinks
      }
    } else {
      Seq(header.id)
    }
  }

  def goodSuperChain(chain: Seq[Header], superChain: Seq[Header], level: Int): Boolean = {
    import org.ergoplatform.settings.Constants.NiPoPowParams._
    superChainQuality(chain, superChain, level)(m, d) && multiLevelQuality(chain, superChain, level)(k1, d)
  }

  private def locallyGood(chainSize: Int, superChainSize: Int, level: Int)(d: Float): Boolean = {
    superChainSize > (1 - d) * math.pow(2, -level) * chainSize
  }

  /** @param chain      - Full chain (C)
    * @param superChain - Super-chain of level µ (C↑µ)
    * @param level      - Level of super-chain (µ) */
  private def superChainQuality(chain: Seq[Header], superChain: Seq[Header], level: Int)(m: Int, d: Float): Boolean = {
    val downChain = chain.dropWhile(_ == superChain.head).takeWhile(_ == superChain.last) // C[C↑µ[0]:C↑µ[−1]], or C'↓
    def checkLocalGoodnessAt(mValue: Int): Boolean = {
      mValue match {
        case mToTest if mToTest < chain.size &&
          locallyGood(math.min(superChain.size, mToTest), math.min(downChain.size, mToTest), level)(d) =>
          checkLocalGoodnessAt(mToTest + 1)
        case mToTest if mToTest < chain.size =>
          false
        case _ =>
          true
      }
    }
    checkLocalGoodnessAt(m)
  }

  private def multiLevelQuality(chain: Seq[Header], superChain: Seq[Header], level: Int)(k1: Int, d: Float): Boolean = {
    val downChain = chain.dropWhile(_ == superChain.head).takeWhile(_ == superChain.last) // C'↓
    def checkQualityAt(levelToCheck: Int): Boolean = {
      levelToCheck match {
        case lvl if lvl > 0 =>
          val subChain = downChain.filter(maxLevelOf(_) >= lvl - 1) // C* = C'↓↑µ'−1
          if (subChain.nonEmpty) {
            val upperSubChainSize = subChain.count(maxLevelOf(_) >= lvl) // |C*↑µ'|
            if (upperSubChainSize >= k1 &&
              !(subChain.count(maxLevelOf(_) >= level) >= (1 - d) * math.pow(2, level - lvl) * upperSubChainSize)) {
              false
            } else {
              checkQualityAt(lvl - 1)
            }
          } else {
            checkQualityAt(lvl - 1)
          }
        case _ =>
          true
      }
    }
    checkQualityAt(level)
  }

}

object NiPoPowProofSerializer extends Serializer[NiPoPowProof] {

  override def toBytes(obj: NiPoPowProof): Array[Byte] = {
    def serializeChain(chain: Seq[Header]) = Ints.toByteArray(chain.size) ++
      Bytes.concat(chain.map(h => Ints.toByteArray(h.bytes.length) ++ h.bytes): _*)
    Bytes.concat(
      Ints.toByteArray(obj.k),
      Ints.toByteArray(obj.m),
      serializeChain(obj.prefix),
      serializeChain(obj.suffix)
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[NiPoPowProof] = Try {
    import cats.implicits._
    val k = Ints.fromByteArray(bytes.take(4))
    val m = Ints.fromByteArray(bytes.slice(4, 8))
    val prefixSize = Ints.fromByteArray(bytes.slice(8, 12))
    val (prefixTryList, suffixBytes) = (0 until prefixSize)
      .foldLeft((List.empty[Try[Header]], bytes.drop(12))) {
        case ((acc, leftBytes), _) =>
          val headerLen = Ints.fromByteArray(leftBytes.take(4))
          val headerTry = HeaderSerializer.parseBytes(leftBytes.slice(4, 4 + headerLen))
          (acc :+ headerTry, leftBytes.drop(4 + headerLen))
      }
    val suffixSize = Ints.fromByteArray(suffixBytes.take(4))
    val suffixTryList = (0 until suffixSize)
      .foldLeft((List.empty[Try[Header]], suffixBytes.drop(4))) {
        case ((acc, leftBytes), _) =>
          val headerLen = Ints.fromByteArray(leftBytes.take(4))
          val headerTry = HeaderSerializer.parseBytes(leftBytes.slice(4, 4 + headerLen))
          (acc :+ headerTry, leftBytes.drop(4 + headerLen))
      }._1
    val prefixTry: Try[List[Header]] = prefixTryList.sequence
    val suffixTry: Try[List[Header]] = suffixTryList.sequence
    prefixTry.flatMap(prefix => suffixTry.map(suffix => NiPoPowProof(k, m, prefix, suffix, Some(bytes.length))))
  }.flatten

}
