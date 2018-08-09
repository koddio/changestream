package changestream.actors

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}
import akka.actor.{Actor, ActorRef, ActorRefFactory, Cancellable}
import changestream.actors.PositionSaver.EmitterResult
import changestream.events.MutationWithInfo
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import com.amazonaws.services.sqs.model.SendMessageBatchResult
import com.github.dwhjames.awswrap.sqs.AmazonSQSScalaClient
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import collection.JavaConverters._
import scala.concurrent.Await

object SqsActor {
  case class FlushRequest(origSender: ActorRef)
  case class BatchResult(queued: Seq[String], failed: Seq[String])
}

class SqsActor(getNextHop: ActorRefFactory => ActorRef,
               config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {
  import SqsActor._

  protected val nextHop = getNextHop(context)
  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val ec = context.dispatcher

  protected val LIMIT = 10
  protected val MAX_WAIT = 250 milliseconds
  protected val TIMEOUT = config.getLong("aws.timeout")


  // Mutable State!
  protected var cancellableSchedule: Option[Cancellable] = None
  protected var lastPosition:String = ""
  // End Mutable State

  protected def setDelayedFlush(origSender: ActorRef) = {
    val scheduler = context.system.scheduler
    cancellableSchedule = Some(scheduler.scheduleOnce(MAX_WAIT) { self ! FlushRequest(origSender) })
  }
  protected def cancelDelayedFlush = cancellableSchedule.foreach(_.cancel())

  protected val messageBuffer = mutable.ArrayBuffer.empty[String]
  protected def getMessageBatch: Seq[(String, String)] = {
    val batchId = Thread.currentThread.getId + "-" + System.nanoTime
    val messages = messageBuffer.zipWithIndex.map {
      case (message, index) => (s"${batchId}-${index}", message)
    }
    messageBuffer.clear()

    messages
  }

  protected val sqsQueue = config.getString("aws.sqs.queue")
  protected val client = new AmazonSQSScalaClient(
    AmazonSQSAsyncClient.
      asyncBuilder().
      withRegion(config.getString("aws.region")).
      build().
      asInstanceOf[AmazonSQSAsyncClient],
    ec
  )
  protected val queueUrl = client.createQueue(sqsQueue)
  queueUrl.failed.map {
    case exception:Throwable =>
      log.error(s"Failed to get or create SQS queue ${sqsQueue}: ${exception.getMessage}")
      throw exception
  }

  override def preStart() = {
    val url = Await.result(queueUrl, TIMEOUT milliseconds)
    log.info(s"Connected to SQS queue ${sqsQueue} with ARN ${url}")
  }
  override def postStop() = {
    import java.util.concurrent.TimeUnit

    cancelDelayedFlush

    // attempt graceful shutdown of the internal client
    val executor = client.client.getExecutorService()
    executor.shutdown()
    executor.awaitTermination(60, TimeUnit.SECONDS)
    client.client.shutdown()
  }

  def receive = {
    case MutationWithInfo(_, pos, _, _, Some(message: String)) =>
      log.debug(s"Received message of size ${message.length}")
      log.trace(s"Received message: ${message}")

      cancelDelayedFlush

      lastPosition = pos
      messageBuffer += message
      messageBuffer.size match {
        case LIMIT => flush(sender())
        case _ => setDelayedFlush(sender())
      }

    case FlushRequest(origSender) =>
      flush(origSender)
  }

  protected def flush(origSender: ActorRef) = {
    log.debug(s"Flushing ${messageBuffer.length} messages to SQS.")

    val position = lastPosition

    val request = for {
      url <- queueUrl
      req <- client.sendMessageBatch(
        url.getQueueUrl,
        getMessageBatch
      )
    } yield req

    request onComplete {
      case Success(result) =>
        val failed = result.getFailed
        if(failed.size() > 0) {
          log.error(s"Some messages failed to enqueue on ${sqsQueue} " +
            s"(sent: ${result.getSuccessful.size()}, failed: ${failed.size()})")
        }
        else {
          log.debug(s"Successfully sent message batch to ${sqsQueue} " +
            s"(sent: ${result.getSuccessful.size()}, failed: ${failed.size()})")
        }
        nextHop ! EmitterResult(position, Some(getBatchResult(result)))
      case Failure(exception) =>
        log.error(s"Failed to send message batch to ${sqsQueue}: ${exception.getMessage}", exception)
        throw exception
    }
  }

  protected def getBatchResult(result: SendMessageBatchResult) = {
    BatchResult(
      result.getSuccessful.asScala.map(_.getMessageId),
      result.getFailed.asScala.map(error => s"${error.getCode}: ${error.getMessage}")
    )
  }
}
