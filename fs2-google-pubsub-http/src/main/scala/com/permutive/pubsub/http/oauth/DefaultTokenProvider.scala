package com.permutive.pubsub.http.oauth

import cats.MonadError
import cats.effect.kernel.{Async, Clock}
import cats.syntax.all._
import com.permutive.pubsub.http.crypto.GoogleAccountParser
import org.http4s.client.Client
import org.typelevel.log4cats.Logger

import java.io.File

class DefaultTokenProvider[F[_]: Clock](
  emailAddress: String,
  scope: List[String],
  auth: OAuth[F]
)(implicit F: MonadError[F, Throwable])
    extends TokenProvider[F] {
  override val accessToken: F[AccessToken] = {
    for {
      now <- Clock[F].realTimeInstant
      token <- auth.authenticate(
        emailAddress,
        scope.mkString(","),
        now.plusMillis(auth.maxDuration.toMillis),
        now
      )
      tokenOrError <- token.fold(F.raiseError[AccessToken](TokenProvider.FailedToGetToken))(_.pure[F])
    } yield tokenOrError
  }
}

object DefaultTokenProvider {
  final private val scope = List("https://www.googleapis.com/auth/pubsub")

  def google[F[_]: Logger: Async](
    serviceAccountPath: String,
    httpClient: Client[F]
  ): F[TokenProvider[F]] =
    for {
      serviceAccount <- GoogleAccountParser.parse(new File(serviceAccountPath).toPath).liftTo[F]
    } yield new DefaultTokenProvider(
      serviceAccount.clientEmail,
      scope,
      new GoogleOAuth(serviceAccount.privateKey, httpClient)
    )

  def instanceMetadata[F[_]: Async: Logger](httpClient: Client[F]): TokenProvider[F] =
    new DefaultTokenProvider[F]("instance-metadata", scope, new InstanceMetadataOAuth[F](httpClient))

  def noAuth[F[_]: Clock](implicit F: MonadError[F, Throwable]): TokenProvider[F] =
    new DefaultTokenProvider("noop", Nil, new NoopOAuth)
}
