package com.permutive.pubsub.producer

import java.util.UUID

import cats.Traverse

trait PubsubProducer[F[_], A] {
  def produce(
    record: A,
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString,
  ): F[String]

  def produceMany[G[_] : Traverse](records: G[Model.Record[A]]): F[List[String]]
}
