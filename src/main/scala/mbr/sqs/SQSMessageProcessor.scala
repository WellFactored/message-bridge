package mbr.sqs

import cats.Applicative
import cats.implicits._
import com.amazonaws.services.sqs.model.Message
import mbr.converters.SQSToRabbitMQConverter
import mbr.rmq.RabbitMQPublisher

sealed trait ProcessingResult[+A]
case class ProcessedSuccessfully[+A]() extends ProcessingResult[A]
case class TransientProcessingFailure[+A](value: A) extends ProcessingResult[A]
case class PermanentProcessingFailure[+A](value: A) extends ProcessingResult[A]

object ProcessingResult {
  def processedSuccessfully[A]: ProcessingResult[A] = ProcessedSuccessfully[A]()
  def transientProcessingFailure[A](value: A): ProcessingResult[A] = TransientProcessingFailure(value)
  def permanentProcessingFailure[A](value: A): ProcessingResult[A] = PermanentProcessingFailure(value)
}

trait SQSMessageProcessor[F[_]] {
  def apply(message: Message): F[ProcessingResult[String]]
}

class ProcessToRabbitMQ[F[_]: Applicative](publisher: RabbitMQPublisher[F]) extends SQSMessageProcessor[F] {
  override def apply(message: Message): F[ProcessingResult[String]] = {
    val messageData = new SQSToRabbitMQConverter(message)
    val success     = ProcessingResult.processedSuccessfully[String]

    if (messageData.alreadyBridged) success.pure[F]
    else {
      (messageData.routingKey, messageData.properties, messageData.body) match {
        case (None, _, _)                 => ProcessingResult.permanentProcessingFailure("No routing key on SQS message").pure[F]
        case (Some(rk), properties, body) => publisher.publish(rk, properties, body).as(success)
      }
    }
  }
}
