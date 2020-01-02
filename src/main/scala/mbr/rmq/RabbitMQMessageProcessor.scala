package mbr.rmq

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import dev.profunktor.fs2rabbit.model.{AckResult, AmqpEnvelope}
import mbr.application.EffectfulLogging
import mbr.converters.RabbitMQToSNSConverter
import mbr.sns.SNSPublisher

import scala.util.control.NonFatal

class RabbitMQMessageProcessor[F[_]: Sync](acker: RabbitMQConsumer.AckerFunction[F], publisher: SNSPublisher[F])(
  implicit me: MonadError[F, Throwable])
    extends EffectfulLogging[F] {

  def process(envelope: AmqpEnvelope[String]): F[Unit] = {
    val messageData = new RabbitMQToSNSConverter(envelope)

    logger.debug(s"Processing message $envelope") >> {
      if (messageData.alreadyBridged) logger.debug("dropping message that has already been bridged")
      else publisher.publish(messageData)
    } >>
      acker(AckResult.Ack(envelope.deliveryTag))
  }.recoverWith {
    case NonFatal(t) =>
      logger.error(t)(s"Error trying to republish message to rabbitmq") >>
        acker(AckResult.NAck(envelope.deliveryTag))
  }
}
