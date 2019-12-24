package mbr.sqs

import cats.Applicative
import cats.implicits._
import com.amazonaws.services.sqs.model.Message
import com.rabbitmq.client.AMQP.BasicProperties

import scala.jdk.CollectionConverters._

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

class ProcessToRabbitMQ[F[_]: Applicative](publisher: RabbitMQPublisher[F], exchangeName: String) extends SQSMessageProcessor[F] {
  override def apply(message: Message): F[ProcessingResult[String]] = {
    val mAttrs = message.getMessageAttributes.asScala.toMap

    mAttrs
      .get("AlreadyBridged")
      .map(_ => ProcessingResult.processedSuccessfully[String].pure[F])
      .getOrElse {
        mAttrs
          .get("RoutingKey")
          .map(_.getStringValue)
          .map { rk =>
            val properties =
              (new BasicProperties)
                .builder()
                .headers(Map[String, AnyRef]("X-ALREADY-BRIDGED" -> "true").asJava)
                .appId(mAttrs.get("AppId").map(_.getStringValue).orNull)
                .contentType(mAttrs.get("ContentType").map(_.getStringValue).orNull)
                .contentEncoding(mAttrs.get("ContentEncoding").map(_.getStringValue).orNull)
                .correlationId(mAttrs.get("CorrelationId").map(_.getStringValue).orNull)
                .expiration(mAttrs.get("Expiration").map(_.getStringValue).orNull)
                .replyTo(mAttrs.get("ReplyTo").map(_.getStringValue).orNull)
                .`type`(mAttrs.get("Type").map(_.getStringValue).orNull)
                .userId(mAttrs.get("UserId").map(_.getStringValue).orNull)
                .build()
            publisher.publish(rk, properties, message.getBody).as(ProcessingResult.processedSuccessfully[String])
          }
          .getOrElse(ProcessingResult.permanentProcessingFailure("No routing key on SQS message").pure[F])
      }
  }
}
