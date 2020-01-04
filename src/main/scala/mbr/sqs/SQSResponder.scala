package mbr.sqs
import cats.effect.Sync
import cats.implicits._
import mbr.application.EffectfulLogging

trait SQSResponder[F[_]] {
  def respond[A](processingResult: ProcessingResult[A]): F[Unit]
}

class LiveSQSResponder[F[_]: Sync](sqsQueue: SQSQueue[F]) extends SQSResponder[F] with EffectfulLogging[F] {
  override def respond[A](processingResult: ProcessingResult[A]): F[Unit] = processingResult match {
    case ProcessedSuccessfully(message) =>
      logger.info(s"successfully processed message") >>
        sqsQueue.deleteMessage(message.getReceiptHandle)

    case TransientProcessingFailure(_, error) =>
      logger.warn(s"Transient failure trying to process message: '$error'") >>
        ().pure[F]

    case PermanentProcessingFailure(message, error) =>
      logger.error(s"Permanent failure trying to process message: '$error' - sending to deadletter queue") >>
        sqsQueue.deadletter(message)
  }
}
