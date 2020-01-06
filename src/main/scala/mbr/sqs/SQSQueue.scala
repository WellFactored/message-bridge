package mbr.sqs

import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Defer, FlatMap, MonadError}
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{Message, ReceiveMessageRequest, SendMessageRequest}
import com.typesafe.scalalogging.StrictLogging
import fs2.Stream
import io.circe.Decoder
import io.circe.generic.semiauto._
import io.circe.parser._
import mbr.application.EffectfulLogging

import scala.jdk.CollectionConverters._

trait SQSQueue[F[_]] {
  def queueUrl: String
  def stream(waitTimeInSeconds:    Int, visibilityTimeoutInSeconds: Int): Stream[F, Message]
  def poll(waitTimeInSeconds:      Int, visibilityTimeoutInSeconds: Int): F[List[Message]]
  def deleteMessage(receiptHandle: String): F[Unit]
  def deadletter(message:          Message): F[Unit]
}

case class RedrivePolicy(deadLetterTargetArn: String, maxReceiveCount: Int)
object RedrivePolicy {
  implicit val decoder: Decoder[RedrivePolicy] = deriveDecoder[RedrivePolicy]
}

class LiveSQSQueue[F[_]](sqs: AmazonSQS, val queueUrl: String, dlQueueUrl: String)(implicit F: Sync[F]) extends SQSQueue[F] with EffectfulLogging[F] {
  override def stream(waitTimeInSeconds: Int, visibilityTimeoutInSeconds: Int): Stream[F, Message] =
    Stream
      .repeatEval(poll(waitTimeInSeconds, visibilityTimeoutInSeconds))
      .evalMap(msgs => FlatMap[F].ifM(msgs.nonEmpty.pure[F])(logger.debug(s"retrieved ${msgs.length} messages"), ().pure[F]).as(msgs))
      .flatMap(Stream.emits)

  override def poll(waitTimeInSeconds: Int, visibilityTimeoutInSeconds: Int): F[List[Message]] = {
    val request = new ReceiveMessageRequest(queueUrl)
    request.setWaitTimeSeconds(waitTimeInSeconds)
    request.setVisibilityTimeout(visibilityTimeoutInSeconds)
    request.setAttributeNames(List("All").asJavaCollection)
    request.setMessageAttributeNames(List("All").asJavaCollection)

    logger.debug("polling for sqs messages") >>
      F.delay(sqs.receiveMessage(request)).map(_.getMessages.asScala.toList)
  }

  override def deleteMessage(receiptHandle: String): F[Unit] =
    F.delay(sqs.deleteMessage(queueUrl, receiptHandle))

  override def deadletter(message: Message): F[Unit] = {
    val sendRequest = new SendMessageRequest(dlQueueUrl, message.getBody)
    sendRequest.setMessageAttributes(message.getMessageAttributes)
    F.delay(sqs.sendMessage(sendRequest)) >> F.delay(sqs.deleteMessage(queueUrl, message.getReceiptHandle))
  }
}

object LiveSQSQueue extends StrictLogging {

  def apply[F[_]: Sync](sqs: AmazonSQS, queueUrl: String)(implicit me: MonadError[F, Throwable]): F[LiveSQSQueue[F]] =
    getQueueAttributes[F](sqs, queueUrl).flatMap { attrs =>
      attrs
        .get("RedrivePolicy")
        .map(s => parse(s).flatMap(_.as[RedrivePolicy]))
        .getOrElse(Left(new Exception(s"Queue $queueUrl does not have a deadletter queue configured"))) match {

        case Left(e) =>
          me.raiseError(e)

        case Right(rd) =>
          val dlqName = rd.deadLetterTargetArn.split(':').last
          logger
            .info(s"Using deadletter queue $dlqName")
            .pure[F]
            .as(new LiveSQSQueue[F](sqs, queueUrl, dlqName))
      }
    }

  private def getQueueAttributes[F[_]: Defer: Applicative](sqs: AmazonSQS, queueUrl: String): F[Map[String, String]] =
    delay[F, Map[String, String]](sqs.getQueueAttributes(queueUrl, List("RedrivePolicy").asJava).getAttributes.asScala.toMap)

  def delay[F[_]: Defer: Applicative, A](f: => A): F[A] =
    Defer[F].defer(Applicative[F].pure(f))
}
