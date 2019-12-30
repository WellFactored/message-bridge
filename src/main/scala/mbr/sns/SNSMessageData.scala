package mbr.sns

import com.amazonaws.services.sns.model.MessageAttributeValue

trait SNSMessageData {
  def alreadyBridged: Boolean
  def attributes:     Map[String, MessageAttributeValue]
  def body:           String
}
