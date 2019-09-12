package com.permutive.pubsub

import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.{Resource, Sync}

private[pubsub] object JavaExecutor {
  final def fixedThreadPool[F[_]](
    size: Int
  )(
    implicit F: Sync[F]
  ): Resource[F, ExecutorService] =
    Resource.make(F.delay(Executors.newFixedThreadPool(size)))(ex => F.delay(ex.shutdown()))
}
