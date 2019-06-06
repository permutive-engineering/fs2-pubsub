package com.permutive.pubsub.http.util

import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.duration.FiniteDuration

case class RefreshableRef[F[_], A](ref: Ref[F, A], cancelToken: F[Unit])

object RefreshableRef {
  def create[F[_]: Concurrent: Timer, A](
    refresh: F[A],
    refreshInterval: FiniteDuration,
    onRefreshError: PartialFunction[Throwable, F[Unit]],
  ): F[RefreshableRef[F, A]] =
    for {
      initial <- refresh
      ref     <- Ref.of(initial)
      fiber   <- updateLoop(refresh, ref, refreshInterval, onRefreshError).start
    } yield RefreshableRef(ref, fiber.cancel)

  def resource[F[_]: Concurrent: Timer, A](
    refresh: F[A],
    refreshInterval: FiniteDuration,
    onRefreshError: PartialFunction[Throwable, F[Unit]],
  ): Resource[F, RefreshableRef[F, A]] =
    Resource.make(create(refresh, refreshInterval, onRefreshError))(refreshableRef => refreshableRef.cancelToken)

  private def updateLoop[F[_]: Sync: Timer, A](
    refresh: F[A],
    ref: Ref[F, A],
    refreshInterval: FiniteDuration,
    onRefreshError: PartialFunction[Throwable, F[Unit]],
  ): F[Unit] = {
    def update(refresh: F[A], ref: Ref[F, A]): F[Unit] =
      for {
        value <- refresh
        _     <- ref.update(_ => value)
      } yield ()

    Timer[F].sleep(refreshInterval) >> update(refresh, ref).recoverWith(onRefreshError) >> updateLoop(
      refresh,
      ref,
      refreshInterval,
      onRefreshError,
    )
  }
}
