package com.permutive.pubsub.producer

import java.util.UUID

import cats.Traverse
import com.permutive.pubsub.producer.Model.MessageId

trait PubsubProducer[F[_], A] {
  def produce(
    record: A,
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString,
  ): F[MessageId]

  def produceMany[G[_] : Traverse](records: G[Model.Record[A]]): F[List[MessageId]]
}
