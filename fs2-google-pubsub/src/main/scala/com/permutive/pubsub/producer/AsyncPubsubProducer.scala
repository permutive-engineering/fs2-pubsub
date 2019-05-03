package com.permutive.pubsub.producer

import java.util.UUID

import cats.Traverse

trait AsyncPubsubProducer[F[_], A] {
  def produceAsync(
    record: A,
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString,
  ): F[F[Unit]]

  def produceManyAsync[G[_] : Traverse](
    records: G[Model.Record[A]],
  ): F[G[F[Unit]]]
}