package com.permutive.pubsub.consumer.decoder

trait MessageDecoder[A] {
  def decode(message: Array[Byte]): Either[Throwable, A]
}

object MessageDecoder {
  def apply[A: MessageDecoder]: MessageDecoder[A] = implicitly
}
