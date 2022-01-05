package com.permutive.pubsub

import com.permutive.pubsub.consumer.decoder.MessageDecoder
import com.permutive.pubsub.producer.encoder.MessageEncoder
import scala.util.Try

case class ValueHolder(value: String) extends AnyVal

object ValueHolder {
  implicit val decoder: MessageDecoder[ValueHolder] = (bytes: Array[Byte]) => {
    Try(ValueHolder(new String(bytes))).toEither
  }

  implicit val encoder: MessageEncoder[ValueHolder] =
    (a: ValueHolder) => Right(a.value.getBytes())
}
