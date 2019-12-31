package mbr.rmq

import cats.Monad
import cats.implicits._
import dev.profunktor.fs2rabbit.model.{AckResult, AmqpEnvelope}
import mbr.converters.RabbitMQToSNSConverter
import mbr.sns.SNSPublisher

class RabbitMQMessageProcessor[F[_]: Monad](acker: RabbitMQConsumer.AckerFunction[F], publisher: SNSPublisher[F]) {
  def process(envelope: AmqpEnvelope[String]): F[Unit] = {
    val messageData = new RabbitMQToSNSConverter(envelope)

    if (messageData.alreadyBridged)
      acker(AckResult.Ack(envelope.deliveryTag))
    else
      publisher.publish(messageData) >>
        acker(AckResult.Ack(envelope.deliveryTag))
  }
}
