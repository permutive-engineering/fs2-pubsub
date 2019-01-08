package com.permutive.pubsub

import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.{Resource, Sync}

import scala.concurrent.duration._

private[pubsub] object JavaExecutor {
  final def fixedThreadPool[F[_]](
    size: Int,
    timeout: FiniteDuration = 30.seconds
  )(
    implicit F: Sync[F],
  ): Resource[F, ExecutorService] = {
    Resource.make(F.delay(Executors.newFixedThreadPool(size)))(ex => F.delay(ex.shutdown()))
  }
}
