package com.permutive.pubsub.consumer.decoder

import cats.Functor

trait MessageDecoder[A] {
  def decode(message: Array[Byte]): Either[Throwable, A]
}

object MessageDecoder {
  def apply[A: MessageDecoder]: MessageDecoder[A] = implicitly

  implicit val functor: Functor[MessageDecoder] = new Functor[MessageDecoder] {

    override def map[A, B](fa: MessageDecoder[A])(f: A => B): MessageDecoder[B] =
      (message: Array[Byte]) => fa.decode(message).map(f)

  }
}
