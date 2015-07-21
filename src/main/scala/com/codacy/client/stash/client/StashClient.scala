package com.codacy.client.stash.client

import com.codacy.client.stash.client.auth.OAuthRSACalculator
import com.codacy.client.stash.util.HTTPStatusCodes
import com.ning.http.client.AsyncHttpClient
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.libs.oauth.{ConsumerKey, RequestToken}
import play.api.libs.ws.ning.NingWSClient

import scala.concurrent.Await
import scala.concurrent.duration._

class StashClient(baseUrl: String, key: String, secretKey: String, token: String, secretToken: String) {

  private lazy val KEY = ConsumerKey(key, secretKey)
  private lazy val TOKEN = RequestToken(token, secretToken)

  private lazy val requestSigner = OAuthRSACalculator(KEY, TOKEN)
  private lazy val requestTimeout = Duration(10, SECONDS)

  /*
   * Does an API request and parses the json output into a class
   */
  def execute[T](request: Request[T])(implicit reader: Reads[T]): RequestResponse[T] = {
    get(request.url) match {
      case Right(json) => RequestResponse(json.asOpt[T])
      case Left(error) => RequestResponse(None, error.message, hasError = true)
    }
  }

  /*
   * Does an paginated API request and parses the json output into a sequence of classes
   */
  def executePaginated[T](request: Request[Seq[T]])(implicit reader: Reads[T]): RequestResponse[Seq[T]] = {
    get(request.url) match {
      case Right(json) =>
        val nextRepos = (for {
          isLastPage <- (json \ "isLastPage").asOpt[Boolean] if !isLastPage
          nextPageStart <- (json \ "nextPageStart").asOpt[String]
        } yield {
            val cleanUrl = request.url.takeWhile(_ != '?')
            val nextUrl = s"$cleanUrl?start=nextPageStart"
            executePaginated(Request(nextUrl, request.classType)).value.getOrElse(Seq.empty)
          }).getOrElse(Seq.empty)

        RequestResponse(Some((json \ "values").as[Seq[T]] ++ nextRepos))

      case Left(error) =>
        RequestResponse[Seq[T]](None, error.message, hasError = true)
    }
  }

  /*
   * Does an API post
   */
  def post[T](request: Request[T], values: JsValue)(implicit reader: Reads[T]): RequestResponse[T] = withClient { client =>
    val url = generateUrl(request.url)
    val jpromise = client.url(url)
      .withFollowRedirects(follow = true)
      .sign(requestSigner)
      .post(values)
    val result = Await.result(jpromise, requestTimeout)

    val value = if (Seq(HTTPStatusCodes.OK, HTTPStatusCodes.CREATED).contains(result.status)) {
      val body = result.body

      val jsValue = parseJson(body)
      jsValue match {
        case Right(responseObj) =>
          RequestResponse(responseObj.asOpt[T])
        case Left(message) =>
          RequestResponse[T](None, message = message.message, hasError = true)
      }
    } else {
      RequestResponse[T](None, result.statusText, hasError = true)
    }
    value
  }

  /* copy paste from post ... */
  def delete[T](requestUrl: String): RequestResponse[Boolean] = withClient { client =>
    val url = generateUrl(requestUrl)
    val jpromise = client.url(url)
      .withFollowRedirects(follow = true)
      .sign(requestSigner)
      .delete()
    val result = Await.result(jpromise, requestTimeout)

    val value = if (Seq(HTTPStatusCodes.OK, HTTPStatusCodes.CREATED, HTTPStatusCodes.NO_CONTENT).contains(result.status)) {
      RequestResponse(Option(true))
    } else {
      RequestResponse[Boolean](None, result.statusText, hasError = true)
    }
    value
  }

  private def get(requestUrl: String): Either[ResponseError, JsValue] = withClient { client =>
    val url = generateUrl(requestUrl)
    val jpromise = client.url(url)
      .withFollowRedirects(follow = true)
      .sign(requestSigner)
      .get()
    val result = Await.result(jpromise, requestTimeout)

    val value = if (Seq(HTTPStatusCodes.OK, HTTPStatusCodes.CREATED).contains(result.status)) {
      val body = result.body
      parseJson(body)
    } else {
      Left(ResponseError(java.util.UUID.randomUUID().toString, result.statusText, result.statusText))
    }
    value
  }

  private def parseJson(input: String): Either[ResponseError, JsValue] = {
    val json = Json.parse(input)

    val errorOpt = (json \ "errors").asOpt[Seq[ResponseError]].flatMap(_.headOption)

    errorOpt.map {
      error =>
        Left(error)
    }.getOrElse(Right(json))
  }

  private def generateUrl(endpoint: String) = s"$baseUrl$endpoint"

  private def withClient[T](block: NingWSClient => T): T = {
    val client = new NingWSClient(new AsyncHttpClient().getConfig)
    val result = block(client)
    client.close()
    result
  }

}