package mbr.converters

import com.amazonaws.services.sqs.model.{Message, MessageAttributeValue}
import com.rabbitmq.client.AMQP.BasicProperties

import scala.jdk.CollectionConverters._

class SQSToRabbitMQConverter(message: Message) {
  val mAttrs: Map[String, MessageAttributeValue] = message.getMessageAttributes.asScala.toMap

  val alreadyBridged: Boolean =
    mAttrs.get("AlreadyBridged").isDefined

  val routingKey: Option[String] =
    mAttrs
      .get("RoutingKey")
      .map(_.getStringValue)

  val properties: BasicProperties =
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

  val body: String = message.getBody
}
