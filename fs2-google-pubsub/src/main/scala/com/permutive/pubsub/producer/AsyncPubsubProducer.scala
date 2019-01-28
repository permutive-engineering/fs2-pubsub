package com.permutive.pubsub.producer

import java.util.UUID

trait AsyncPubsubProducer[F[_], A] {
  def produceAsync(
    record: A,
    callback: F[Unit],
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString,
  ): F[Unit]

  def produceManyAsync(
    records: List[Model.AsyncRecord[F, A]],
  ): F[Unit]
}