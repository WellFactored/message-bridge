package mbr.rmq

import com.rabbitmq.client.AMQP.BasicProperties

trait RabbitMQMessageData {
  def alreadyBridged: Boolean
  def routingKey:     Option[String]
  def properties:     BasicProperties
  def body:           String
}
