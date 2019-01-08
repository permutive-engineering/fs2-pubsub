package com.permutive.pubsub.consumer.http.internal

import com.permutive.pubsub.consumer.http.internal.Model.{AckId, PullResponse}

trait PubsubReader[F[_]] {
  def read: F[PullResponse]
  def ack(ackId: List[AckId]): F[Unit]
  def nack(ackId: List[AckId]): F[Unit]
}

object PubsubReader {
  def apply[F[_]: PubsubReader]: PubsubReader[F] = implicitly
}
