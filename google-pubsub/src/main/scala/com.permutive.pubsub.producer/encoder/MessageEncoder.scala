package com.permutive.pubsub.producer.encoder

trait MessageEncoder[A] {
  def encode(a: A): Either[Throwable, Array[Byte]]
}

object MessageEncoder {
  def apply[A: MessageEncoder]: MessageEncoder[A] = implicitly
}
