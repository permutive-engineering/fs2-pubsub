package com.permutive.pubsub

import cats.effect.{Resource, Sync}
import cats.syntax.all._

import scala.concurrent.ExecutionContext

private[pubsub] object ThreadPool {
  final def blockingThreadPool[F[_] : Sync](
    parallelism: Int
  ): Resource[F, ExecutionContext] = {
    JavaExecutor
      .fixedThreadPool(parallelism)
      .map(ExecutionContext.fromExecutor)
  }
}
