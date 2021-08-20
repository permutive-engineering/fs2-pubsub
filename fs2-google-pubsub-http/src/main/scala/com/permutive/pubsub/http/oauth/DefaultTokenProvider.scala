package com.permutive.pubsub.http.oauth

import cats.effect.Sync
import cats.syntax.all._
import com.permutive.pubsub.http.crypto.GoogleAccountParser
import io.chrisdavenport.log4cats.Logger
import org.http4s.client.Client

import java.io.File
import java.time.Instant

class DefaultTokenProvider[F[_]: Sync](
  emailAddress: String,
  scope: List[String],
  auth: OAuth[F]
) extends TokenProvider[F] {
  override val accessToken: F[AccessToken] = {
    for {
      now <- Sync[F].delay(Instant.now())
      token <- auth.authenticate(
        emailAddress,
        scope.mkString(","),
        now.plusMillis(auth.maxDuration.toMillis),
        now
      )
      tokenOrError <- token.fold(Sync[F].raiseError[AccessToken](TokenProvider.FailedToGetToken))(_.pure[F])
    } yield tokenOrError
  }
}

object DefaultTokenProvider {
  final private val scope = List("https://www.googleapis.com/auth/pubsub")

  def google[F[_]: Logger: Sync](
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

  def instanceMetadata[F[_]: Sync: Logger](httpClient: Client[F]): TokenProvider[F] =
    new DefaultTokenProvider[F]("instance-metadata", scope, new InstanceMetadataOAuth[F](httpClient))

  def noAuth[F[_]: Sync]: TokenProvider[F] =
    new DefaultTokenProvider("noop", Nil, new NoopOAuth)
}
