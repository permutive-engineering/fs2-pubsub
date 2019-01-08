package com.permutive.pubsub

import cats.effect.{ContextShift, Resource, Sync}

import scala.concurrent.ExecutionContext

private[pubsub] object ThreadPool {
  final def blockingThreadPool[F[_] : Sync](parallelism: Int)(
    implicit CX: ContextShift[F],
  ): Resource[F, ExecutionContext] = {
    JavaExecutor
      .fixedThreadPool(parallelism)
      .flatMap(ec => Resource.pure(ExecutionContext.fromExecutor(ec)))
  }
}
