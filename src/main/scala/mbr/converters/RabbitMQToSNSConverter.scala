package mbr.converters

import com.amazonaws.services.sns.model.MessageAttributeValue
import dev.profunktor.fs2rabbit.model
import dev.profunktor.fs2rabbit.model.AmqpEnvelope
import mbr.sns.SNSMessageData

class RabbitMQToSNSConverter(envelope: AmqpEnvelope[String]) extends SNSMessageData {

  private val properties: model.AmqpProperties = envelope.properties

  override val alreadyBridged: Boolean =
    properties.headers.get("X-ALREADY-BRIDGED").isDefined

  override val attributes: Map[String, MessageAttributeValue] =
    Map.empty[String, MessageAttributeValue] ++
      properties.appId.map(stringAttribute("AppId", _)) ++
      properties.clusterId.map(stringAttribute("ClusterId", _)) ++
      properties.contentEncoding.map(stringAttribute("ContentEncoding", _)) ++
      properties.contentType.map(stringAttribute("ContentType", _)) ++
      properties.correlationId.map(stringAttribute("CorrelationId", _)) ++
      properties.expiration.map(stringAttribute("Expiration", _)) ++
      properties.replyTo.map(stringAttribute("ReplyTo", _)) +
      stringAttribute("RoutingKey", envelope.routingKey.value) ++
      properties.`type`.map(stringAttribute("Type", _)) ++
      properties.userId.map(stringAttribute("UserId", _)) +
      stringAttribute("X-ALREADY-BRIDGED", "true")

  private def stringAttribute(name: String, value: String): (String, MessageAttributeValue) =
    (name, new MessageAttributeValue().withStringValue(value).withDataType("String"))

  override val body: String =
    envelope.payload

}
