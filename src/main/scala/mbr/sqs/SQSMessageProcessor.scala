package mbr.sqs

import com.amazonaws.services.sqs.model.Message

sealed trait ProcessingResult
case object ProcessedSuccessfully extends ProcessingResult
case class TransientProcessingFailure(msg: String) extends ProcessingResult
case class PermanentProcessingFailure(msg: String) extends ProcessingResult

trait SQSMessageProcessor[F[_]] {
  def apply(message: Message): F[ProcessingResult]
}
