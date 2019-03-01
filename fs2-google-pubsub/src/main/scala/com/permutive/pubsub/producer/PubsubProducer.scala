package com.permutive.pubsub.producer

import java.util.UUID

import fs2.Chunk

trait PubsubProducer[F[_], A] {
  def produce(
    record: A,
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString,
  ): F[String]

  def produceMany(records: List[Model.Record[A]]): F[List[String]]

  def produceMany(records: Chunk[Model.Record[A]]): F[List[String]]
}
