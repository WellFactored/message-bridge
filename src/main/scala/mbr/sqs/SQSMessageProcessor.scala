package mbr.sqs

import cats.effect.Sync
import cats.implicits._
import com.amazonaws.services.sqs.model.Message
import mbr.application.EffectfulLogging
import mbr.converters.SQSToRabbitMQConverter
import mbr.rmq.RabbitMQPublisher

sealed trait ProcessingResult[+A]
case class ProcessedSuccessfully[+A](message:      Message) extends ProcessingResult[A]
case class TransientProcessingFailure[+A](message: Message, error: A) extends ProcessingResult[A]
case class PermanentProcessingFailure[+A](message: Message, error: A) extends ProcessingResult[A]

object ProcessingResult {
  def processedSuccessfully[A](message:      Message): ProcessingResult[A] = ProcessedSuccessfully[A](message)
  def transientProcessingFailure[A](message: Message, error: A): ProcessingResult[A] = TransientProcessingFailure(message, error)
  def permanentProcessingFailure[A](message: Message, error: A): ProcessingResult[A] = PermanentProcessingFailure(message, error)
}

trait SQSMessageProcessor[F[_]] {
  def apply(message: Message): F[ProcessingResult[String]]
}

class ProcessToRabbitMQ[F[_]: Sync](publisher: RabbitMQPublisher[F]) extends SQSMessageProcessor[F] with EffectfulLogging[F] {
  override def apply(message: Message): F[ProcessingResult[String]] = {
    val messageData = new SQSToRabbitMQConverter(message)
    val success     = ProcessingResult.processedSuccessfully[String](message)

    if (messageData.alreadyBridged) logger.debug("dropping message that has already been bridged").as(success)
    else {
      (messageData.routingKey, messageData.properties, messageData.body) match {
        case (None, _, _)                 => ProcessingResult.permanentProcessingFailure(message, "No routing key on SQS message").pure[F]
        case (Some(rk), properties, body) => publisher.publish(rk, properties, body).as(success)
      }
    }
  }
}
