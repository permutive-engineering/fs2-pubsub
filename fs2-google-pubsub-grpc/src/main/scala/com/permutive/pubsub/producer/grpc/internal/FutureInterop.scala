package com.permutive.pubsub.producer.grpc.internal

import cats.effect.Async
import cats.syntax.all._
import com.google.api.core.{ApiFuture, ApiFutureCallback, ApiFutures}
import com.google.common.util.concurrent.MoreExecutors

private[internal] object FutureInterop {
  def fFromFuture[F[_]: Async, A](future: F[ApiFuture[A]]): F[A] =
    future.flatMap { futA =>
      Async[F].async[A](addCallback(futA))
    }

  @inline
  private def addCallback[A](futA: ApiFuture[A])(cb: Either[Throwable, A] => Unit): Unit =
    ApiFutures.addCallback(
      futA,
      new ApiFutureCallback[A] {
        override def onFailure(t: Throwable): Unit =
          cb(Left(t))

        override def onSuccess(result: A): Unit =
          cb(Right(result))
      },
      // We use the `directExecutor` as this is the location to run the callback _after_ completion*. In our case that
      // means where to run `onFailure` and `onSuccess` which are both lightweight. The underlying library uses this
      // executor for similarly simple functions.
      //
      // * From a docstring used by `addCallback` (`Futures.addCallback`):
      //    `The executor to run {@code callback} when the future completes.`
      //
      // See docstring of `ListenableFuture.addListener` for more details on this being safe.
      // As above see https://permutive.atlassian.net/browse/PLAT-255 for further details on deciding this.
      MoreExecutors.directExecutor(),
    )
}
