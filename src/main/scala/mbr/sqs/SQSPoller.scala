package mbr.sqs

import cats.effect.{ContextShift, Fiber, IO, Timer}
import cats.implicits._
import mbr.application.{EffectfulLogging, ThreadPools}

import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters._

trait SQSPoller[F[_]] {
  def start: F[Fiber[F, Nothing]]
}

class LiveSQSPoller(
  sqsQueue:  SQSQueue[IO],
  processor: SQSMessageProcessor[IO]
) extends SQSPoller[IO]
    with EffectfulLogging {

  def start: IO[Fiber[IO, Nothing]] =
    ThreadPools.fixedThreadPool(1).use { implicit ec =>
      implicit val timer: Timer[IO]        = IO.timer(ec)
      implicit val cs:    ContextShift[IO] = IO.contextShift(ec)
      logger.info(s"Starting SQS listener on queue ${sqsQueue.queueUrl}") >>
        loop.start(cs)
    }

  /**
    * Continually poll the queue for messages to be processed. The `receiveMessage` call will block for the
    * configured wait time when there are no messages available, so it's okay to just recurse without delay
    *
    * `IO.flatMap` is stack safe, so no worries about the recursion
    */
  private def loop(implicit timer: Timer[IO]): IO[Nothing] =
    poll(20, 30).recoverWith {
      case NonFatal(t) =>
        logger.error(t)("Caught an exception in the sqs poll loop - recovering") >>
          logger.error(t.getMessage) >>
          IO.sleep(10 seconds)
    } >> loop

  private def poll(waitTimeInSeconds: Int, visibilityTimeoutInSeconds: Int): IO[Unit] =
    sqsQueue.poll(waitTimeInSeconds, visibilityTimeoutInSeconds).flatMap { messages =>
      logger.info(s"Received ${messages.length} messages").whenA(messages.nonEmpty) >>
        messages.traverse { message =>
        logger.info(s"${message.getMessageAttributes.asScala}") >>
          processor(message).flatMap {
            case ProcessedSuccessfully() =>
              logger.info(s"successfully forwarded message") >>
                sqsQueue.deleteMessage(message.getReceiptHandle)

            case TransientProcessingFailure(msg) =>
              logger.warn(s"Transient failure trying to process message: '$msg'") >>
                IO.unit

            case PermanentProcessingFailure(msg) =>
              logger.error(s"Permanent failure trying to process message: '$msg' - sending to deadletter queue") >>
                sqsQueue.deadletter(message)
          }
        }.void
    }
}
