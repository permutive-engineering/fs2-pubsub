package com.permutive.pubsub.producer

import java.util.UUID

import com.permutive.pubsub.producer.Model.Record

trait PubsubProducer[F[_], A] {
  def produce(
    record: A,
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString,
  ): F[String]

  def produce(records: List[Record[A]]): F[List[String]]
}
