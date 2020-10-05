package org.ergoplatform.http.api

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.{Directive, Route}
import akka.pattern.ask
import io.circe.{Encoder, Json}
import io.circe.syntax._
import org.ergoplatform.{ErgoAddressEncoder, ErgoBox}
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.ErgoReadersHolder.{GetReaders, Readers}
import org.ergoplatform.nodeView.mempool.{ErgoMemPoolReader, FeeHistogramBin}
import org.ergoplatform.nodeView.mempool.OrderedTxPool.WeightedTxId
import org.ergoplatform.nodeView.state.{ErgoStateReader, UtxoStateReader}
import org.ergoplatform.nodeView.wallet.AugWalletTransaction
import org.ergoplatform.nodeView.wallet.AugWalletTransaction.boxEncoder
import scorex.core.NodeViewHolder.ReceivableMessages.LocallyGeneratedTransaction
import scorex.core.api.http.ApiError.BadRequest
import scorex.core.api.http.ApiResponse
import scorex.core.settings.RESTApiSettings

import scala.concurrent.Future

case class TransactionsApiRoute(readersHolder: ActorRef, nodeViewActorRef: ActorRef, settings: RESTApiSettings)
                               (implicit val context: ActorRefFactory) extends ErgoBaseApiRoute with ApiCodecs {

  override val route: Route = pathPrefix("transactions") {
    checkTransactionR ~ getUnconfirmedTransactionsR ~ sendTransactionR ~
    getFeeHistogramR ~ getRecommendedFeeR ~ getExpectedWaitTimeR
  }

  private def getMemPool: Future[ErgoMemPoolReader] = (readersHolder ? GetReaders).mapTo[Readers].map(_.m)

  private def getStateAndPool: Future[(ErgoStateReader, ErgoMemPoolReader)] =
    (readersHolder ? GetReaders).mapTo[Readers].map { rs =>
      (rs.s, rs.m)
    }

  private def getUnconfirmedTransactions(offset: Int, limit: Int): Future[Json] = getMemPool.map { p =>
    p.getAll.slice(offset, offset + limit).map(_.asJson).asJson
  }

  private def validateTransactionAndProcess(tx: ErgoTransaction)(processFn: ErgoTransaction => Any): Route = {
    onSuccess {
      getStateAndPool
        .map {
          case (utxo: UtxoStateReader, mp: ErgoMemPoolReader) =>
            utxo.withTransactions(mp.getAll).validate(tx)
          case _ =>
            tx.statelessValidity
        }
    } {
      _.fold(
        e => BadRequest(s"Malformed transaction: ${e.getMessage}"),
        _ => {
          processFn(tx)
          ApiResponse(tx.id)
        }
      )
    }
  }

  def sendTransactionR: Route = (pathEnd & post & entity(as[ErgoTransaction])) { tx =>
    validateTransactionAndProcess(tx) { tx =>
      nodeViewActorRef ! LocallyGeneratedTransaction[ErgoTransaction](tx)
    }
  }

  def checkTransactionR: Route = (path("check") & post & entity(as[ErgoTransaction])) { tx =>
    validateTransactionAndProcess(tx) { tx => tx }
  }

  def getUnconfirmedTransactionsR: Route = (path("unconfirmed") & get & paging) { (offset, limit) =>
    ApiResponse(getUnconfirmedTransactions(offset, limit))
  }

  val feeHistogramParameters: Directive[(Int, Long)] = parameters("bins".as[Int] ? 10, "maxtime".as[Long] ? (60*1000L))

  def getFeeHistogram(nBins : Int, maxWaitTimeMsec: Long, wtxs : Seq[WeightedTxId]): Array[FeeHistogramBin] = {
    val histogram = Array.fill(nBins + 1)(FeeHistogramBin(0,0))
    val now = System.currentTimeMillis()
    val interval = maxWaitTimeMsec / nBins
    for (wtx <- wtxs) {
      val waitTime = now - wtx.created
      val bin = if (waitTime < maxWaitTimeMsec) (waitTime/interval).asInstanceOf[Int] else nBins
      histogram(bin).nTxns += 1
      histogram(bin).totalFee += wtx.feePerKb
    }
    histogram
  }

  implicit val encodeHistogramBeam: Encoder[FeeHistogramBin] = new Encoder[FeeHistogramBin] {
    final def apply(bin:FeeHistogramBin): Json = Json.obj(
      ("nTxns", bin.nTxns.asJson),
      ("totalFee", bin.totalFee.asJson)
    )
  }

  def getFeeHistogramR: Route = (path("poolhistogram") & get & feeHistParam) { (bins, maxtime) =>
    ApiResponse(getMemPool.map(p => getFeeHistogram(bins, maxtime, p.weightedTransactionIds(Int.MaxValue)).asJson))
  }

  val feeRequestParameters: Directive[(Int, Int)] = parameters("waitTime".as[Int] ? 1, "txSize".as[Int] ? 100)

  def getRecommendedFeeR: Route = (path("getfee") & get & feeRequest) { (waitTime, txSize) =>
    ApiResponse(getMemPool.map(_.getRecommendedFee(waitTime,txSize).asJson))
  }

  val waitTimeRequestParameters: Directive[(Long, Int)] = parameters("fee".as[Long] ? 1000L, "txSize".as[Int] ? 100)

  def getExpectedWaitTimeR: Route = (path("waittime") & get & waitTimeRequest) { (fee, txSize) =>
    ApiResponse(getMemPool.map(_.getExpectedWaitTime(fee,txSize).asJson))
  }
}
