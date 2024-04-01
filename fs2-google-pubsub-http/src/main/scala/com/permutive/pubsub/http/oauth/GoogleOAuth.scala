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

import cats.Applicative

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.Instant
import java.util.Date
import cats.effect.kernel.{Async, Sync}
import cats.syntax.all._
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import org.typelevel.log4cats.Logger
import org.http4s.Method.POST
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class GoogleOAuth[F[_]: Async: Logger](
  key: RSAPrivateKey,
  httpClient: Client[F]
) extends OAuth[F] {
  import GoogleOAuth._

  object clientDsl extends Http4sClientDsl[F]
  import clientDsl._

  final private[this] val algorithm            = Algorithm.RSA256(null: RSAPublicKey, key)
  final private[this] val googleOAuthDomainStr = "https://www.googleapis.com/oauth2/v4/token"
  final private[this] val googleOAuthDomain    = Uri.unsafeFromString(googleOAuthDomainStr)

  @deprecated(
    "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
    since = "0.22.2"
  )
  final override def authenticate(
    iss: String,
    scope: String,
    exp: Instant,
    iat: Instant
  ): F[Option[AccessToken]] = {
    val tokenF = Sync[F].delay(
      JWT.create
        .withIssuedAt(Date.from(iat))
        .withExpiresAt(Date.from(exp))
        .withAudience(googleOAuthDomainStr)
        .withClaim("scope", scope)
        .withClaim("iss", iss)
        .sign(algorithm)
    )

    val request =
      tokenF.map { token =>
        POST(
          UrlForm(
            "grant_type" -> "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion"  -> token
          ),
          googleOAuthDomain
        )
      }

    httpClient
      .expectOr[Array[Byte]](request) { resp =>
        resp.as[String].map(FailedRequest.apply)
      }
      .flatMap(bytes => Sync[F].delay(readFromArray[AccessToken](bytes)).map(_.some))
      .handleErrorWith { e =>
        Logger[F].warn(e)("Failed to retrieve JWT Access Token from Google") >> Applicative[F].pure(none[AccessToken])
      }
  }

  final override val maxDuration: FiniteDuration = 1.hour
}

object GoogleOAuth {
  case class FailedRequest(body: String)
      extends RuntimeException(s"Failed request, got response: $body")
      with NoStackTrace
}
