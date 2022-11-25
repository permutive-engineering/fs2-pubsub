/*
 * Copyright 2018 Permutive
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
      serviceAccount <- Async[F]
        .blocking(GoogleAccountParser.parse(new File(serviceAccountPath).toPath))
        .flatMap(Async[F].fromEither)
    } yield new DefaultTokenProvider(
      serviceAccount.clientEmail,
      scope,
      new GoogleOAuth(serviceAccount.privateKey, httpClient)
    )
  def fromString[F[_]: Logger: Async](
    serviceAccountJson: String,
    httpClient: Client[F]
  ): F[TokenProvider[F]] =
    for {
      serviceAccount <- GoogleAccountParser.parseString(serviceAccountJson).liftTo[F]
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
